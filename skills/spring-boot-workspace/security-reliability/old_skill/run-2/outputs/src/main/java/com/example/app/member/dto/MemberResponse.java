package com.example.app.member.dto;

import java.time.Instant;

/**
 * 회원 응답 DTO. 비밀번호(해시 포함)는 절대 노출하지 않는다.
 */
public record MemberResponse(
        Long id,
        String email,
        String nickname,
        Instant createdAt
) {}
