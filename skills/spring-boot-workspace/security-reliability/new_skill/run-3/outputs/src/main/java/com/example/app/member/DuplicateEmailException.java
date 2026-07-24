package com.example.app.member;

// 순수 도메인 예외 — HTTP를 알지 못한다. 상태코드 매핑은 전역 예외 핸들러가 담당한다.
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("이미 등록된 이메일입니다: " + email);
    }
}
