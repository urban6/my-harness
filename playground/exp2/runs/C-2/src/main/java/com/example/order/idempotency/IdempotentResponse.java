package com.example.order.idempotency;

/** 저장/재생되는 2xx 응답. body는 직렬화된 JSON 원문, locationPath는 경로만(E5). */
public record IdempotentResponse(int status, String body, String locationPath) {
}
