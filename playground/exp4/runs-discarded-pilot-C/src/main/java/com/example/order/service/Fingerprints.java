package com.example.order.service;

import com.example.order.web.dto.OrderCreateRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** 요청 지문 = SHA-256(v1|X-User-Id|METHOD|정규화 경로|정규화 본문) 소문자 hex (01 문서 11절). */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String orderCreate(String userId, OrderCreateRequest req) {
        String items = req.items().stream()
                .map(i -> i.productId() + ":" + i.quantity())
                .collect(Collectors.joining(","));
        String coupon = req.couponCode() == null ? "-" : req.couponCode();
        return sha256("v1|" + nz(userId) + "|POST|/api/orders|" + items + "|" + coupon);
    }

    public static String pay(String userId, long orderId, String cardToken) {
        return sha256("v1|" + nz(userId) + "|POST|/api/orders/" + orderId + "/pay|" + cardToken);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
