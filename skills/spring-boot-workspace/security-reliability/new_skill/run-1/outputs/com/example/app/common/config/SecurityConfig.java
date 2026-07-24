package com.example.app.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해싱을 위한 PasswordEncoder 빈.
 * 전체 시큐리티 필터체인 없이 spring-security-crypto의 인코더만 사용한다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt — 솔트 내장, 널리 쓰이는 기본값.
        return new BCryptPasswordEncoder();
    }
}
