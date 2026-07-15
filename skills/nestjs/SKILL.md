---
name: nestjs
description: NestJS로 REST API·서비스·영속성 코드를 작성/수정할 때 따르는 관용 패턴·레시피. 모듈·컨트롤러·프로바이더 구조, DTO·검증(class-validator), 예외 필터(RFC 9457), Prisma 영속성, 설정, 테스트 규약을 제공. 대상 프로젝트가 NestJS일 때 발동.
---

# nestjs

NestJS 프로젝트에서 백엔드 코드를 **일관되고 관용적으로** 작성하기 위한 참조 스킬입니다. `backend-impl` 에이전트가 API 계약(`01_api_design.md`)과 데이터 모델(`03_db_design.md`)을 실제 코드로 옮길 때 이 스킬의 레시피를 따릅니다.

## 언제 발동하나

다음이 보이는 프로젝트에서 엔드포인트·서비스·영속성 코드를 작성/수정할 때:
- `package.json`에 `@nestjs/core`, `@nestjs/common` 의존성
- `nest-cli.json`, `main.ts`의 `NestFactory.create(...)` 부트스트랩
- `*.module.ts` / `*.controller.ts` / `*.service.ts` 파일 관례

`backend-impl`이 스택을 NestJS로 감지하면 이 스킬을 로드해 절차에 반영합니다.

## 전제

- **TypeScript** — `strict` 모드 기준. 데코레이터 사용(`experimentalDecorators`, `emitDecoratorMetadata` 활성).
- **영속성은 Prisma 중심** — `PrismaService` + `schema.prisma`. 프로젝트가 TypeORM/Mikro-ORM이면 동일 원칙(영속성 경계·리포지토리 추상화)을 그 도구 관용구로 옮긴다.
- **검증은 class-validator + class-transformer**, 전역 `ValidationPipe`.
- **테스트는 Jest**(+ supertest e2e).

## 핵심 원칙

1. **기존 패턴 모방이 최우선.** 이 스킬의 템플릿은 관례가 없을 때의 기본값이다. 프로젝트에 이미 모듈 경계·에러 포맷·네이밍 관례가 있으면 **그것을 따른다**.
2. **모듈 경계 준수.** 기능은 `feature.module.ts`로 캡슐화하고, `controller → service → (prisma) repository` 단방향 의존. 서비스/도메인이 컨트롤러에 의존하지 않는다.
3. **생성자 주입(DI).** 프로바이더는 `constructor(private readonly x: X)`로 주입. 수동 `new`로 의존성을 만들지 않는다.
4. **DTO로 경계 분리.** 요청/응답 DTO 클래스를 두고, Prisma 모델(엔티티)을 요청/응답에 그대로 노출하지 않는다.
5. **검증은 전역 ValidationPipe.** `whitelist: true`, `transform: true`로 DTO 밖 필드를 걸러내고 타입 변환.
6. **에러는 RFC 9457로 통일.** 예외 필터에서 `application/problem+json` 형태로 매핑한다. `api-designer`의 기본 에러 포맷과 정렬된다.
7. **비밀값은 외부화.** 자격증명·토큰을 코드/커밋에 하드코딩하지 않고 `ConfigModule`+환경변수로.

## 작업별 참조

| 작업 | 참조 |
| --- | --- |
| 모듈 구조·CLI·tsconfig 전제 | `references/project-layout.md` |
| 컨트롤러·요청/응답 DTO·검증·상태코드 | `references/web-layer.md` |
| 서비스(프로바이더)·DI·트랜잭션 | `references/service-layer.md` |
| Prisma 스키마·PrismaService·쿼리·페이지네이션 | `references/persistence-layer.md` |
| 예외 필터·에러 응답(RFC 9457) | `references/exception-handling.md` |
| ConfigModule·환경변수·시크릿 외부화 | `references/configuration.md` |
| Jest 단위·e2e·테스트 DB | `references/testing.md` |

## 팀 정렬

- **입력**: `api-designer`의 `01_api_design.md`(엔드포인트·상태코드), `db-migrator`의 `03_db_design.md`(스키마·엔터티)를 그대로 코드/`schema.prisma`에 반영한다.
- **에러 포맷**: `api-designer` 기본값 **RFC 9457**과 일치시킨다(`exception-handling.md`).
- **테스트 작성 주체는 `test-writer`.** `references/testing.md`는 **규약·패턴 참조용**이다. 이 스킬은 backend-impl이 기존 테스트를 실행해 회귀를 확인하는 것까지 돕지만, 새 테스트 작성은 `test-writer`에 위임한다.

## 검증

구현 후 반드시 빌드·기존 테스트를 실행해 회귀가 없는지 확인한다:
- 빌드/타입체크: `npm run build` (tsc) — 타입 에러가 없어야 한다.
- 린트: `npm run lint`
- 테스트: `npm test`(단위) / `npm run test:e2e`(e2e)
- Prisma: 스키마 변경 시 `npx prisma generate`, 마이그레이션은 `npx prisma migrate dev` / 배포는 `migrate deploy`.

실행한 명령과 결과를 그대로 보고한다. 통과했다고 꾸미지 않는다.
