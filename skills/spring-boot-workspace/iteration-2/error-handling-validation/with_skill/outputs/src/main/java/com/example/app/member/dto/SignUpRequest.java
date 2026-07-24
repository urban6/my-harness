package com.example.app.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 필수값 누락 → @NotBlank(400), 이메일 형식 오류 → @Email(400).
// 검증 실패는 MethodArgumentNotValidException → GlobalExceptionHandler가 400으로 매핑.
public record SignUpRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String nickname
) {}
