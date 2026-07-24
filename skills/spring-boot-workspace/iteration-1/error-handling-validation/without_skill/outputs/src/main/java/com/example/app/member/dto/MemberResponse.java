package com.example.app.member.dto;

import com.example.app.member.domain.Member;

public class MemberResponse {

    private final Long id;
    private final String email;
    private final String nickname;

    public MemberResponse(Long id, String email, String nickname) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
    }

    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getId(), member.getEmail(), member.getNickname());
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }
}
