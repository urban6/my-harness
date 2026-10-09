package com.example.order.idempotency;

/** 업무 처리의 성공(2xx) 결과. location 은 없을 수 있다. */
public record IdemResult(int status, Object body, String location) {
}
