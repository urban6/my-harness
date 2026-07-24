package com.example.app.member.controller;

import com.example.app.member.dto.MemberResponse;
import com.example.app.member.dto.SignUpRequest;
import com.example.app.member.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping
    public ResponseEntity<MemberResponse> signUp(
            @Valid @RequestBody SignUpRequest request,
            UriComponentsBuilder uriBuilder) {

        MemberResponse response = memberService.signUp(request);

        URI location = uriBuilder.path("/members/{id}")
                .buildAndExpand(response.getId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }
}
