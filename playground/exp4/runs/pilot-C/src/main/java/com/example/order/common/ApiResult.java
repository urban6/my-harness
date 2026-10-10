package com.example.order.common;

/** 멱등성 저장/재생 대상이 되는 2xx 응답: 상태코드, JSON 본문 문자열, Location(경로만). */
public record ApiResult(int status, String body, String location) {
}
