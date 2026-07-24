package com.example.app.member;

import com.example.app.member.dto.CreateMemberRequest;
import com.example.app.member.dto.MemberResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    // 회원 가입: 201 + Location.
    // 검증 실패(400)·이메일 중복(409)은 GlobalExceptionHandler가 매핑한다.
    @PostMapping
    public ResponseEntity<MemberResponse> register(@Valid @RequestBody CreateMemberRequest request) {
        MemberResponse created = memberService.register(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }
}
