package com.example.order.idempotency;

/** 같은 요청인지 판정하는 재료(R4.2): X-User-Id · 경로 · 본문. */
public record RequestFingerprint(String userId, String path, Object body) {
}
