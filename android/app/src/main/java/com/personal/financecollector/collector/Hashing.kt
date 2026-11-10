package com.personal.financecollector.collector

import java.security.MessageDigest

object Hashing {
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
