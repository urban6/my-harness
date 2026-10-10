package com.example.order.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 01 설계 6.3: sha256(scope \n POST \n canonicalPath \n (N|U:userId) \n canonicalBody) */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(IdempotencyScope scope, String canonicalPath, String userId, String canonicalBody) {
        String raw = scope.name() + "\n" + "POST" + "\n" + canonicalPath + "\n"
                + (userId == null ? "N" : "U:" + userId) + "\n" + canonicalBody;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
