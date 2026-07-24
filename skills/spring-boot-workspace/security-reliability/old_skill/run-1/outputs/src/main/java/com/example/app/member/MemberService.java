package com.example.app.member;

import com.example.app.member.dto.MemberResponse;
import com.example.app.member.dto.SignUpRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public MemberResponse signUp(SignUpRequest request) {
        // 사전 중복 검사 — 흔한 경우를 빠르게 걸러 409로 응답.
        if (memberRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        // 평문 비밀번호는 저장하지 않는다 — BCrypt 해시로 변환해 보관.
        String passwordHash = passwordEncoder.encode(request.password());
        Member member = new Member(request.email(), passwordHash, request.nickname());

        try {
            Member saved = memberRepository.save(member);
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            // 동시 가입 경합 등으로 유니크 제약을 위반한 경우도 409로 통일.
            throw new DuplicateEmailException(request.email());
        }
    }

    private MemberResponse toResponse(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getCreatedAt()
        );
    }
}
