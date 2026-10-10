package com.example.order.service;

/** 멱등 레코드에 저장되는(그리고 최초 응답으로 나가는) 응답: 상태코드 + 본문 문자열 + Location. */
public record StoredResponse(int status, String contentType, String location, String body) {

    public static final String JSON = "application/json";

    public static StoredResponse json(int status, String location, String body) {
        return new StoredResponse(status, JSON, location, body);
    }
}
