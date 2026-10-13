# 기능: 상품(Product) 등록·조회 (파일럿 축소본)

## 배경
상품을 등록하고 조회할 수 있는 백엔드 API가 필요하다.

## 스택
- Java 21, Spring Boot 3.x, JPA, PostgreSQL, Flyway
- 테스트: JUnit 5 + Testcontainers(PostgreSQL)
- 테스트 명령: ./gradlew test

## 범위
- 포함: 상품 등록·조회
- 제외: 주문, 인증·인가, 상품 수정·삭제

## 공통 규약
- 금액(`price`)은 정수(원 단위)다.
- DB 접속 정보는 표준 Spring 속성(`spring.datasource.url`·`username`·`password`)으로 받으며, 실행 시 환경 변수(`SPRING_DATASOURCE_URL` 등)로 덮어쓸 수 있어야 한다. 서버 포트도 `SERVER_PORT`로 덮어쓸 수 있어야 한다.

## 요구사항
| ID | 기능 | 계약 | 규칙 |
|---|---|---|---|
| R1 | 상품 등록 | POST /api/products {name, price, stock} → 201 + Location, 본문은 R2 응답과 같은 형태 | name 필수(공백만 있는 문자열 불가), price > 0, stock ≥ 0. 위반 시 400 |
| R2 | 상품 조회 | GET /api/products/{id} → 200 {id, name, price, stock} | 없으면 404 |
| R8 | 에러 포맷 | R1~R2에서 정의한 모든 에러 응답 + 요청 본문 JSON 파싱 실패(400) | RFC 9457 Problem Details, Content-Type: application/problem+json, 최소 필드 type·title·status·detail |

## 완료 조건
- ./gradlew test 통과
- R1·R2·R8 각각을 검증하는 테스트가 있을 것
