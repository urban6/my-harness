package com.example.app.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 회원 가입 요청 바디.
 * - email: 필수, 이메일 형식
 * - password: 필수
 * - nickname: 필수
 * 검증 실패 시 MethodArgumentNotValidException → 400(ProblemDetail).
 */
public record CreateMemberRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        String nickname
) {}
