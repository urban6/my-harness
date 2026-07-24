package com.example.app.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해싱 설정.
 * spring-security-crypto 의 BCryptPasswordEncoder 만 사용한다
 * (전체 시큐리티 스타터를 넣지 않아 엔드포인트가 기본 잠기지 않는다).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
