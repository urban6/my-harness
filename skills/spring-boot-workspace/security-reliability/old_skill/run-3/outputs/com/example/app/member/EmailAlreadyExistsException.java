package com.example.app.member;

/**
 * 이미 등록된 이메일로 가입을 시도할 때 던지는 도메인 예외.
 * HTTP를 알지 못한다 — 409 매핑은 전역 예외 핸들러에서 한다.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("이미 등록된 이메일입니다: " + email);
    }
}
