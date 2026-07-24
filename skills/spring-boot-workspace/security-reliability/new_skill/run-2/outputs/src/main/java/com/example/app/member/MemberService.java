package com.example.app.member;

import com.example.app.member.dto.MemberResponse;
import com.example.app.member.dto.SignUpRequest;

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
        // 이메일 중복 → 도메인 예외(409는 핸들러가 매핑)
        if (memberRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        // 비밀번호는 평문이 아닌 해시로 저장한다.
        String passwordHash = passwordEncoder.encode(request.password());

        Member member = new Member(request.email(), passwordHash, request.nickname());
        Member saved = memberRepository.save(member);

        return toResponse(saved);
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
