package com.example.order.idempotency;

/** 저장·재생되는 2xx 응답. location은 null일 수 있다. */
public record StoredResponse(int status, String bodyJson, String location) {
}
