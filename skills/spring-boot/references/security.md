# 보안 기본 — 비밀번호 해싱 · 민감 데이터

이 문서는 CRUD·회원가입 같은 **일반 기능을 구현할 때 놓치기 쉬운 보안 기본**을 다룬다. 전체 인증/인가(Spring Security 필터체인, OAuth2, JWT)는 프로젝트가 그것을 도입한 경우 그 관례를 따르고, 여기서는 **데이터를 안전하게 다루는 최소 원칙**에 집중한다.

## 원칙

- **비밀번호·시크릿을 평문으로 저장하지 않는다.** 비밀번호는 단방향 해시(`PasswordEncoder`)로 저장한다.
- **민감 값을 응답에 노출하지 않는다.** 비밀번호 해시조차 응답 DTO에 넣지 않는다(원칙: 응답에는 필요한 필드만).
- **민감 값을 로그에 남기지 않는다.** 요청 바디·엔티티를 통째로 로깅하면 비밀번호·토큰이 샐 수 있다.
- **"나중에 해시" 주석으로 미루지 않는다.** 회원가입·비밀번호 변경 기능을 만들면 **그 자리에서** 해싱을 코드로 반영한다. 평문 저장 코드는 그 자체로 결함이다.

## 비밀번호 해싱 — PasswordEncoder

`spring-security-crypto`(또는 `spring-boot-starter-security`)의 `PasswordEncoder`를 빈으로 등록하고 서비스에서 주입해 쓴다. 기본 선택은 `BCryptPasswordEncoder`(또는 `PasswordEncoderFactories.createDelegatingPasswordEncoder()`).

```java
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt — 솔트 내장, 널리 쓰이는 기본값
        return new BCryptPasswordEncoder();
    }
}
```

의존성이 없으면 최소한 `spring-security-crypto`만 추가해도 `PasswordEncoder`를 쓸 수 있다(전체 시큐리티 스타터가 부담되면).

```kotlin
// build.gradle.kts
implementation("org.springframework.security:spring-security-crypto")
```

### 저장 시 — 해시해서 저장

```java
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
        if (memberRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        String encoded = passwordEncoder.encode(request.password());   // 평문 아님
        Member member = new Member(request.email(), encoded, request.nickname());
        return toResponse(memberRepository.save(member));
    }
}
```

### 검증 시 — matches로 비교

```java
if (!passwordEncoder.matches(rawPassword, member.getPasswordHash())) {
    throw new InvalidCredentialsException();
}
```

- 저장된 해시를 **복호화하지 않는다**(단방향). 로그인 검증은 `matches(raw, hash)`로 한다.
- 엔티티 필드명을 `passwordHash`처럼 두면 "이건 해시"라는 의도가 드러난다.

## 응답·직렬화에서 민감 필드 제외

- 응답 DTO(record)에 비밀번호/해시 필드를 **아예 넣지 않는 것**이 가장 안전하다(`web-layer.md`의 "노출할 필드만" 원칙).
- 엔티티를 직접 직렬화해야 하는 불가피한 경우에만 `@JsonIgnore`를 쓴다 — 하지만 기본은 DTO 분리.

```java
public record MemberResponse(Long id, String email, String nickname, Instant createdAt) {}
// password/passwordHash 필드 없음
```

## 체크리스트

- [ ] 비밀번호가 `PasswordEncoder.encode()`로 해시되어 저장되는가(평문 저장 없음)?
- [ ] 로그인/검증이 `matches()`로 이뤄지는가(복호화 시도 없음)?
- [ ] 비밀번호·해시·토큰이 응답 DTO/바디에 노출되지 않는가?
- [ ] 요청 바디·엔티티를 통째로 로깅해 민감 값이 새지 않는가?
- [ ] 자격증명·키가 코드/커밋에 하드코딩되지 않았는가(`configuration.md`)?
