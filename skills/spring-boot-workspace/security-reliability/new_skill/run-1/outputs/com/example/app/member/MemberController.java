package com.example.app.member;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.example.app.member.dto.MemberResponse;
import com.example.app.member.dto.SignUpRequest;

import jakarta.validation.Valid;

/**
 * 회원 관련 HTTP 엔드포인트. 얇게 유지하고 로직은 서비스로 위임한다.
 */
@RestController
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    // 회원 가입: 201 Created + Location 헤더
    @PostMapping
    public ResponseEntity<MemberResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        MemberResponse created = memberService.signUp(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }
}
