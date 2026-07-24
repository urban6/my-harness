# 설정 — application.yml · 프로파일 · 시크릿

## application.yml

```yaml
spring:
  application:
    name: order-service
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/app}
    username: ${DB_USERNAME:app}
    password: ${DB_PASSWORD}          # 기본값 없음 — 반드시 환경에서 주입
  jpa:
    hibernate:
      ddl-auto: validate              # 운영은 validate/none. update·create 지양
    properties:
      hibernate:
        format_sql: true
    open-in-view: false               # OSIV 끔 — 지연 로딩 경계를 서비스로 강제

server:
  port: ${PORT:8080}
```

- `${VAR:default}` 문법으로 환경변수 주입. **비밀값(비밀번호·토큰)은 기본값을 주지 않는다.**
- `open-in-view: false` 권장 — 뷰/직렬화 시점의 우발적 지연 로딩을 막고 트랜잭션 경계를 명확히.

## 프로파일

환경별 설정은 `application-{profile}.yml`로 분리한다.

```
application.yml            # 공통
application-local.yml      # 로컬 개발
application-prod.yml       # 운영
```

- 활성화: `SPRING_PROFILES_ACTIVE=prod` 또는 `--spring.profiles.active=prod`.
- 테스트는 `@ActiveProfiles("test")`로 지정.

## 타입 안전 설정 — @ConfigurationProperties

`@Value` 산발보다 그룹화된 설정을 record로 바인딩한다.

```java
@ConfigurationProperties(prefix = "app.order")
public record OrderProperties(
        Duration reservationTimeout,
        int maxItemsPerOrder
) {}
```
```java
@SpringBootApplication
@ConfigurationPropertiesScan          // 또는 @EnableConfigurationProperties(OrderProperties.class)
public class AppApplication { }
```
```yaml
app:
  order:
    reservation-timeout: 15m
    max-items-per-order: 50
```

## 시크릿 외부화 (중요)

- 자격증명·API 키·토큰을 **소스/커밋에 하드코딩하지 않는다**(핵심 원칙 8 "비밀값 외부화"와 정렬).
- 주입 경로: 환경변수 → 외부 시크릿 매니저(Vault, AWS/GCP Secrets Manager 등) → CI/CD 시크릿.
- `application-*.yml`에 실제 비밀값을 커밋하지 않는다. 예시는 `application-example.yml`이나 `.env.example`로.

## 스키마 마이그레이션

- 운영에서 `ddl-auto`로 스키마를 바꾸지 않는다. **Flyway** 또는 **Liquibase**로 버전 관리:
  ```
  src/main/resources/db/migration/V1__init.sql   # Flyway 관례
  ```
- DB 스키마 설계/마이그레이션 DDL이 있으면 이 위치로 옮긴다.

## 체크리스트

- [ ] 비밀값이 코드/커밋에 하드코딩되지 않고 환경에서 주입되는가?
- [ ] 운영 `ddl-auto`가 `validate`/`none`인가?
- [ ] `open-in-view: false`인가(또는 프로젝트 관례)?
- [ ] 환경별 설정이 프로파일로 분리됐는가?
- [ ] 그룹 설정을 `@ConfigurationProperties`로 타입 안전하게 바인딩했는가?
