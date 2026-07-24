package com.example.app.member.exception;

// HTTP를 모르는 순수 도메인 예외. 상태코드 매핑은 GlobalExceptionHandler에서 한다.
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("이미 등록된 이메일입니다: " + email);
    }
}
