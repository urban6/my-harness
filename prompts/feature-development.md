# 기능 개발

## A — feature-pm에 전체 위임

```text
feature-pm 에이전트로 {기능명} 기능을 개발해줘.
스택은 {스택}이고, 범위는 {포함 / 제외}야.

Phase 0에서 스택을 확정해 모든 워커에 주입하고,
설계(backend-designer) → 구현·검증(backend-impl·boundary-verifier) → 테스트(test-writer) 순으로 진행해줘.
각 Phase 산출물 경로와 마지막에 {테스트 명령} 결과를 그대로 보여줘.
```

## B — 단계 직접 지정

```text
{기능명} 기능을 설계부터 구현까지 진행해줘.
스택은 {스택}, {범위}야.

1) backend-designer로 엔드포인트·요청/응답 스키마·에러 응답과
   테이블·인덱스·제약을 한 번에 정리. 정합 요약표까지 남겨줘.
2) 그 산출물을 입력으로 {구현 스킬 또는 backend-impl}로 구현
3) architecture-expert로 {레이어·의존성 규칙}이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 {테스트 명령} 결과를 그대로 보여줘.
```

예시:

```text
주문(Order) API를 설계부터 구현까지 진행해줘.
스택은 Spring Boot 3.x + JPA + PostgreSQL이야.

1) backend-designer로 엔드포인트·요청/응답 스키마·에러 응답(RFC 9457)과
   테이블·인덱스·제약을 한 번에 정리. JPA 엔티티 + Flyway SQL 기준으로.
2) 그 산출물을 입력으로 spring-boot 스킬을 따라 컨트롤러·서비스·리포지토리 구현
3) architecture-expert로 web → service → repository 단방향이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 ./gradlew test 결과를 그대로 보여줘.
```

## C — 설계 확정 후 단일 작업

```text
## 작업
{구현 대상 — 클래스·메서드 단위}만 구현해줘.

## 설계 (내가 정한 것)
- {핵심 방식 — 어떤 기술·알고리즘으로}
- {실패·예외 시 동작}
- {시그니처·반환값}
- {이번 범위 밖인 것}

## 제약
- {스택·라이브러리}
- {반드시 지켜야 할 규칙}

## 범위
- 수정 가능: {수정해도 되는 파일}
- 생성 가능: {새로 만들어도 되는 파일 — 예외·테스트 등}
- 금지: 그 외 기존 파일 수정

## 완료 조건
- {통과해야 할 테스트 — 결과로 판정할 수 있는 문장}
- {테스트 환경·명령} 결과를 그대로 보여줘.
```

예시:

```text
## 작업
OrderService.reserveSeat()만 구현해줘.

## 설계 (내가 정한 것)
- Redis SETNX + TTL 180초로 좌석 선점
- 키는 seat:{showId}:{seatId}, 값은 요청마다 새로 만든 UUID 토큰
- 선점 성공 시 토큰을 반환, 실패 시 SeatAlreadyHeldException
- DB 확정은 이 메서드 범위 밖

## 제약
- Spring Boot 3, Lettuce 사용
- 락 해제는 본인 토큰일 때만 (Lua 스크립트)

## 범위
- 수정 가능: OrderService
- 생성 가능: SeatAlreadyHeldException, 해제용 Lua 스크립트, OrderService 테스트
- 금지: 그 외 기존 파일 수정

## 완료 조건
- 동시 요청 100개 중 정확히 1개만 성공하는 테스트 포함
- Testcontainers Redis로 ./gradlew test 실행 결과를 그대로 보여줘.
```
