package com.personal.finance.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    /** Compares via digests so the comparison time does not depend on how much of the secret matches. */
    public static boolean secretsMatch(String a, String b) {
        byte[] da = sha256Hex(a).getBytes(StandardCharsets.UTF_8);
        byte[] db = sha256Hex(b).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(da, db);
    }
}
