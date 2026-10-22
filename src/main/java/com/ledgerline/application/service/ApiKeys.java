package com.ledgerline.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys are 32 random bytes (256 bits). Only SHA-256(key) is stored. A fast hash is correct here:
 * slow password hashes (bcrypt, argon2) exist to protect LOW-entropy human passwords from guessing;
 * a 256-bit random key cannot be guessed, and a fast hash keeps per-request authentication cheap.
 */
public final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "ll_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
