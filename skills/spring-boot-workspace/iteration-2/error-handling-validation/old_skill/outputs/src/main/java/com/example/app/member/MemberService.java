package com.example.app.member;

import com.example.app.member.dto.CreateMemberRequest;
import com.example.app.member.dto.MemberResponse;
import com.example.app.member.exception.DuplicateEmailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)          // 클래스 기본: 읽기 전용
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional                        // 쓰기 메서드만 오버라이드
    public MemberResponse register(CreateMemberRequest request) {
        // 이메일 중복 → 도메인 예외(핸들러가 409로 매핑)
        if (memberRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        Member member = new Member(
                request.email(),
                passwordEncoder.encode(request.password()),   // 비밀번호는 해시로 저장
                request.nickname()
        );
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
