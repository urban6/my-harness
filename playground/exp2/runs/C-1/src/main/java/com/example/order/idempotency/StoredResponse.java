package com.example.order.idempotency;

/** 멱등 처리 결과(최초 응답 또는 재생). body는 JSON 문자열 원문. */
public record StoredResponse(int status, String body, String location) {
}
