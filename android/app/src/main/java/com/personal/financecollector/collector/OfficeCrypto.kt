package com.personal.financecollector.collector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

sealed interface OfficeResult {
    class Opened(val bytes: ByteArray) : OfficeResult
    data object NeedsPassword : OfficeResult
    data object WrongPassword : OfficeResult
    class Unsupported(val reason: String) : OfficeResult
}

/** Opens a password-protected .xlsx (Office "Agile" encryption, MS-OFFCRYPTO 2.3.4.10) using only the platform's crypto. */
object OfficeCrypto {
    private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val BLOCK_VERIFIER_INPUT = byteArrayOf(0xfe.toByte(), 0xa7.toByte(), 0xd2.toByte(), 0x76, 0x3b, 0x4b, 0x9e.toByte(), 0x79)
    private val BLOCK_VERIFIER_VALUE = byteArrayOf(0xd7.toByte(), 0xaa.toByte(), 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e)
    private val BLOCK_KEY = byteArrayOf(0x14, 0x6e, 0x0b, 0xe7.toByte(), 0xab.toByte(), 0xac.toByte(), 0xd0.toByte(), 0xd6.toByte())
    private const val SEGMENT = 4096
    private const val MAX_SPIN = 2_000_000

    fun isCompoundFile(b: ByteArray): Boolean = b.size > 512 && MAGIC.indices.all { b[it] == MAGIC[it] }

    fun open(file: ByteArray, password: String?): OfficeResult {
        val streams = try {
            CompoundFile(file).streams(setOf("EncryptionInfo", "EncryptedPackage"))
        } catch (e: Exception) {
            return OfficeResult.Unsupported("This file could not be read.")
        }
        val info = streams["EncryptionInfo"]
        val pkg = streams["EncryptedPackage"]
        if (info == null || pkg == null) return OfficeResult.Unsupported("This is an old Excel (.xls) file. Open it in Excel and save it as .xlsx or CSV.")
        if (password.isNullOrEmpty()) return OfficeResult.NeedsPassword
        return try {
            decrypt(info, pkg, password)
        } catch (e: Exception) {
            OfficeResult.Unsupported("This kind of protection is not supported. Save the statement as CSV from Excel and import that.")
        }
    }

    private fun attrs(tag: String): Map<String, String> =
        Regex("""([A-Za-z]+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun hashName(xml: String) = when (xml.uppercase()) {
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> throw IllegalArgumentException("hash")
    }

    private fun digest(alg: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(alg)
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun fit(b: ByteArray, size: Int): ByteArray = if (b.size >= size) b.copyOf(size) else b + ByteArray(size - b.size) { 0x36 }

    private fun intLe(i: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()

    private fun aesDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun decrypt(info: ByteArray, pkg: ByteArray, password: String): OfficeResult {
        val bb = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        val major = bb.getShort(0).toInt()
        val minor = bb.getShort(2).toInt()
        if (major != 4 || minor != 4) return OfficeResult.Unsupported("This kind of protection is not supported. Save the statement as CSV from Excel and import that.")
        val xml = String(info, 8, info.size - 8, StandardCharsets.UTF_8)
        val keyData = attrs(Regex("""<(?:\w+:)?keyData\b[^>]*>""").find(xml)!!.value)
        val enc = attrs(Regex("""<(?:\w+:)?encryptedKey\b[^>]*>""").find(xml)!!.value)
        val b64 = Base64.getDecoder()

        val alg = hashName(enc.getValue("hashAlgorithm"))
        val spin = enc.getValue("spinCount").toInt().also { require(it in 0..MAX_SPIN) }
        val keyBytes = enc.getValue("keyBits").toInt() / 8
        val blockSize = enc.getValue("blockSize").toInt()
        val salt = b64.decode(enc.getValue("saltValue"))
        val iv = fit(salt, blockSize)

        var h = digest(alg, salt, password.toByteArray(StandardCharsets.UTF_16LE))
        for (i in 0 until spin) h = digest(alg, intLe(i), h)
        fun keyFor(block: ByteArray) = fit(digest(alg, h, block), keyBytes)

        val verifierInput = aesDecrypt(keyFor(BLOCK_VERIFIER_INPUT), iv, b64.decode(enc.getValue("encryptedVerifierHashInput")))
        val verifierValue = aesDecrypt(keyFor(BLOCK_VERIFIER_VALUE), iv, b64.decode(enc.getValue("encryptedVerifierHashValue")))
        val expected = digest(alg, verifierInput)
        if (!MessageDigest.isEqual(expected, verifierValue.copyOf(expected.size))) return OfficeResult.WrongPassword

        val secret = aesDecrypt(keyFor(BLOCK_KEY), iv, b64.decode(enc.getValue("encryptedKeyValue"))).copyOf(keyBytes)

        val dataAlg = hashName(keyData.getValue("hashAlgorithm"))
        val dataSalt = b64.decode(keyData.getValue("saltValue"))
        val dataBlock = keyData.getValue("blockSize").toInt()
        val total = ByteBuffer.wrap(pkg).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        require(total in 0..Int.MAX_VALUE.toLong())
        val out = java.io.ByteArrayOutputStream(total.toInt())
        var offset = 8
        var index = 0
        while (offset < pkg.size && out.size() < total) {
            val len = minOf(SEGMENT, pkg.size - offset) / 16 * 16
            if (len == 0) break
            val segIv = fit(digest(dataAlg, dataSalt, intLe(index)), dataBlock)
            out.write(aesDecrypt(secret, segIv, pkg.copyOfRange(offset, offset + len)))
            offset += SEGMENT
            index++
        }
        return OfficeResult.Opened(out.toByteArray().copyOf(total.toInt()))
    }
}

/** Just enough of the OLE2 compound-file layout to pull named streams out of it. */
internal class CompoundFile(private val d: ByteArray) {
    private val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize = 1 shl bb.getShort(0x1E).toInt()
    private val miniSize = 1 shl bb.getShort(0x20).toInt()
    private val miniCutoff = bb.getInt(0x38)
    private val fat: IntArray

    init {
        require(sectorSize == 512 || sectorSize == 4096)
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) bb.getInt(0x4C + i * 4).takeIf { it >= 0 }?.let { fatSectors += it }
        var difat = bb.getInt(0x44)
        var guard = 0
        while (difat >= 0 && guard++ < 10_000) {
            val base = offset(difat)
            for (i in 0 until sectorSize / 4 - 1) bb.getInt(base + i * 4).takeIf { it >= 0 }?.let { fatSectors += it }
            difat = bb.getInt(base + sectorSize - 4)
        }
        fat = IntArray(fatSectors.size * sectorSize / 4)
        fatSectors.forEachIndexed { n, s -> for (i in 0 until sectorSize / 4) fat[n * sectorSize / 4 + i] = bb.getInt(offset(s) + i * 4) }
    }

    private fun offset(sector: Int) = (sector + 1) * sectorSize

    private fun chain(start: Int, table: IntArray): List<Int> {
        val out = ArrayList<Int>()
        var s = start
        while (s >= 0 && out.size <= table.size) {
            out += s
            s = table[s]
        }
        return out
    }

    private fun read(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (s in chain(start, fat)) out.write(d, offset(s), sectorSize)
        val all = out.toByteArray()
        return if (size in 0..all.size.toLong()) all.copyOf(size.toInt()) else all
    }

    fun streams(wanted: Set<String>): Map<String, ByteArray> {
        val dir = read(bb.getInt(0x30), -1)
        val miniFat = read(bb.getInt(0x3C), -1).let { b -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).let { x -> IntArray(b.size / 4) { x.getInt(it * 4) } } }
        var mini = ByteArray(0)
        val found = HashMap<String, ByteArray>()
        val entries = dir.size / 128
        val dbb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until entries) {
            val at = n * 128
            if (dir[at + 0x42].toInt() == 5) mini = read(dbb.getInt(at + 0x74), dbb.getInt(at + 0x78).toLong() and 0xFFFFFFFFL)
        }
        for (n in 0 until entries) {
            val at = n * 128
            if (dir[at + 0x42].toInt() != 2) continue
            val nameLen = dbb.getShort(at + 0x40).toInt() and 0xFFFF
            val name = String(dir, at, maxOf(0, nameLen - 2), StandardCharsets.UTF_16LE)
            if (name !in wanted) continue
            val start = dbb.getInt(at + 0x74)
            val size = dbb.getInt(at + 0x78).toLong() and 0xFFFFFFFFL
            found[name] = if (size < miniCutoff) {
                val out = java.io.ByteArrayOutputStream()
                for (s in chain(start, miniFat)) out.write(mini, s * miniSize, miniSize)
                out.toByteArray().copyOf(size.toInt())
            } else read(start, size)
        }
        return found
    }
}
