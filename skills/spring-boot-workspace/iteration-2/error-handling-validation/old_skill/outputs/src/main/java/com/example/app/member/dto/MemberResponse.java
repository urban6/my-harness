package com.example.app.member.dto;

import java.time.Instant;

// 응답 — 노출할 필드만. 비밀번호는 절대 응답에 포함하지 않는다.
public record MemberResponse(
        Long id,
        String email,
        String nickname,
        Instant createdAt
) {}
