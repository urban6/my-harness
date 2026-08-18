# my-harness

**새 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code용 커스텀 에이전트·스킬·커맨드를 한곳에서 작성·검증·관리합니다. 여기 구성요소는 특정 프로젝트에 종속되지 않은 **재사용 기본값**이며, 새 프로젝트에는 심링크로 가져다 씁니다. **작성 → 검증 → 적용**의 순환으로 굴러갑니다.

## 구조

```
my-harness/
├── agents/     # 서브에이전트 정의 (.md, 파일 하나 = 에이전트 하나)
├── skills/     # 재사용 작업 절차 묶음 (SKILL.md + 참고 자료)
├── commands/   # 슬래시 커맨드 (/이름)
├── install.sh  # 구성요소를 사용처로 심링크하는 설치 스크립트
└── CLAUDE.md   # 이 레포 작업 시 지침
```

## 구성요소

| 유형 | 구성요소 |
| --- | --- |
| **agents** | `feature-pm` · `api-designer` · `ui-designer` · `db-migrator` · `backend-impl` · `frontend-impl` · `boundary-verifier` · `test-suite` · `architecture-expert` · `debugger` · `performance-optimizer` |
| **skills** | `nestjs` · `spring-boot` (백엔드 관용 패턴 참조) |
| **commands** | `commit` · `push` |

> 상세 사용법은 각 파일의 frontmatter `description`을 참고하세요.

## 오케스트레이션

에이전트 여러 개를 팀으로 굴리려면 프롬프트에서 **이름으로 지목**해야 합니다. 자동 트리거는 신뢰도가 낮아, `~~ 개발해줘`처럼 두루뭉술하게 요청하면 메인 에이전트가 혼자 처리합니다. 또 팀은 **메인 세션에서 시작**해야 합니다 — 서브에이전트는 추가 스폰이 막혀 있습니다(`agents/feature-pm.md`).

이름 지목에 더해 "병렬로"·"나눠서"나 "구현 → 검증 → 테스트" 같은 순서를 함께 적으면 더 확실합니다.

### 1. 오케스트레이터에게 맡기기

`feature-pm`이 요구 분해 → 설계(`api-designer`·`ui-designer`·`db-migrator`) → 구현·검증(`backend-impl`·`frontend-impl`·`boundary-verifier`) → 테스트(`test-suite`)를 Phase 0~4로 스폰·조율합니다.

워커끼리는 `SendMessage`로 직접 대화하고, 산출물은 `_workspace/features/{name}/`에 단계별로 쌓입니다.

```text
feature-pm 에이전트로 "로그인" 기능을 풀스택으로 개발해줘 —
요구 분해 → 설계 → 구현 → 검증 → 통합까지 Phase 파이프라인으로.
```

### 2. 파이프라인 직접 엮기

오케스트레이터 없이, 프롬프트에 순서만 적어 가벼운 협업을 만듭니다. 진단 에이전트(`debugger`·`performance-optimizer`·`architecture-expert`)는 독립 실행이라 이렇게 엮기 좋습니다.

```text
결제 API가 느려 — debugger로 원인 찾고, performance-optimizer로
병목 진단한 다음, architecture-expert 관점에서 구조 개선안까지 정리해줘.
```

## 예시 — 스프링부트 백엔드 API 개발

스킬과 에이전트는 발동 방식이 다릅니다. `spring-boot` 스킬은 프로젝트의 스택 신호(`build.gradle`의 `org.springframework.boot`, `@RestController` 등)로 **자동 발동**하지만, 에이전트는 앞서 말한 대로 **이름으로 지목**해야 합니다.

그래서 원하는 결과를 얻는 프롬프트는 대체로 이 네 조각으로 이뤄집니다 — **스택 명시 + 에이전트 이름 + 단계 순서 + 산출물 형태**.

### 프롬프트 작성 원칙

```text
✗ 상품 API 만들어줘
```

```text
✓ 상품(Product) REST API를 추가해줘. Spring Boot 3.x + JPA + PostgreSQL,
  백엔드 전용이야(프런트 없음).
  생성·단건 조회·목록 조회(페이지네이션)·삭제 네 개고, 에러 응답은
  ProblemDetail로 통일해줘.
  다 만든 뒤 ./gradlew test 를 실제로 돌려서 명령과 결과를 그대로 보여줘.
```

- **스택을 문장으로 박는다** — `Spring Boot 3.x + JPA + PostgreSQL, 백엔드 전용`. 아래 각주에 적은 이유로, 파이프라인 워커를 쓸 때는 특히 필수입니다.
- **에이전트는 이름으로 지목한다** — 자동 트리거는 신뢰도가 낮습니다.
- **단계를 번호로 적는다** — `1) 설계 → 2) 구현 → 3) 검증`.
- **산출물 형태를 지정한다** — `file:line 근거로`, `PASS/FIX/REDO로 판정만`, `안 A/B/C로 비교`.
- **검증 명령을 명시한다** — "`./gradlew test` 돌리고 결과 그대로". 스킬 자체가 실제 실행을 요구하지만, 프롬프트에 박아두면 확실합니다.

### 1. 신규 API 기능 개발

기능 하나 정도면 에이전트 없이 **스킬 단독**으로 충분합니다. 위 "좋은 예"가 그대로 이 경우입니다 — 스택 신호만 있으면 `spring-boot` 스킬이 레이어링·record DTO·`ProblemDetail`·트랜잭션 규약을 알아서 따릅니다.

설계를 코드보다 먼저 확정하고 싶다면 파이프라인으로 엮습니다. 스킬의 `산출물 정렬` 규약이 상류 설계 문서를 입력으로 받도록 돼 있어 잘 물립니다.

```text
주문(Order) API를 설계부터 구현까지 진행해줘.
스택은 Spring Boot 3.x + JPA + PostgreSQL, 백엔드 전용이야 — 프런트 워커는 빼줘.

1) api-designer로 엔드포인트·요청/응답 스키마·에러 응답(RFC 9457)을 문서로 먼저 정리
2) db-migrator로 테이블·인덱스·제약을 잡아줘. 단 Drizzle 말고
   JPA 엔티티 + Flyway 마이그레이션 SQL 기준으로 써줘.
3) 그 두 산출물을 입력으로 spring-boot 스킬을 따라 컨트롤러·서비스·리포지토리 구현
4) architecture-expert로 web → service → repository 단방향이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 ./gradlew test 결과를 그대로 보여줘.
```

2번의 `단, Drizzle 말고 ~ 기준으로`가 이 섹션의 핵심입니다. 워커의 기본 전제를 프롬프트에서 덮어쓰는 문장이 없으면 엉뚱한 스택 산출물이 나옵니다.

### 2. 기존 코드 리뷰 · 보안 점검

진단 에이전트와 스킬의 체크리스트를 함께 태웁니다. 스킬 참조 문서는 **경로로 직접 지목**해야 안정적으로 걸립니다.

```text
방금 만든 회원가입·로그인 API를 커밋 전에 점검해줘.
대상은 src/main/java/com/example/member/** 와 관련 테스트야.

1) architecture-expert로 web → service → repository 단방향이 깨진 곳,
   JPA 엔티티가 컨트롤러 경계 밖으로 새는 곳을 찾아줘.
2) spring-boot 스킬의 references/security.md 와 references/exception-handling.md
   체크리스트를 기준으로, 비밀번호 해싱·응답 민감필드 노출·ProblemDetail 일관성을
   항목별로 점검해줘.
3) boundary-verifier로 API 문서의 응답 스키마와 실제 DTO 필드가 일치하는지 교차 검증.
   판정(PASS/FIX/REDO)만 하고 수정은 하지 마 — 고칠지는 내가 정할게.

발견 항목은 file:line 근거와 함께 심각도 순으로 정리해줘.
```

### 3. 디버깅 · 성능 개선

진단 에이전트 셋은 모두 **읽기 전용**이라 순서대로 엮기 좋습니다. 스프링 특유의 관찰 지점(연관관계 fetch 전략, 실제 발생 쿼리)을 프롬프트에 심어주면 진단 품질이 올라갑니다.

```text
주문 목록 API(GET /orders)가 데이터 늘면서 느려졌어. 순서대로 진행해줘.

1) debugger로 재현 경로를 잡고 느린 구간을 file:line으로 특정
2) performance-optimizer로 N+1·페이지네이션·인덱스 관점 병목을 우선순위로 진단.
   JPA 연관관계 fetch 전략과 실제 발생 쿼리 로그까지 봐줘.
3) architecture-expert 관점에서 구조적 개선안(조회 전용 모델 분리·캐시 등)을
   트레이드오프로 비교

셋 다 진단만 하고 코드는 고치지 마. 마지막에 내가 고를 수 있게 안 A/B/C로 정리해줘.
```

> `backend-impl`·`db-migrator`·`test-suite`는 Next.js·Drizzle·Vitest·MSW를 전제로 작성돼 있습니다. 스프링부트에서는 위 예시처럼 프롬프트에서 스택을 덮어쓰거나 그 워커를 빼세요. 같은 이유로 프런트 워커까지 스폰하는 `feature-pm`은 백엔드 전용 작업에 과합니다 — 파이프라인을 직접 엮는 편이 낫습니다.

## 적용 방법

`install.sh`가 구성요소를 사용처로 **심링크**합니다. 심링크라서 이 레포만 고쳐도 연결된 모든 곳에 바로 반영됩니다. 구성요소가 아닌 것(유형 디렉터리의 안내용 `README.md`, `*-workspace/` 평가 부산물)은 자동 제외됩니다.

```bash
# 전역 — 모든 프로젝트에서 사용 (~/.claude 로 링크, 기본값)
./install.sh install

# 프로젝트별 — .claude/ 로만 링크 (경로 생략 시 현재 디렉터리)
./install.sh install --project
./install.sh install --project ~/path/to/project

# 미리보기 · 현황 · 제거
./install.sh install --global --dry-run   # 변경 없이 수행 예정만 출력
./install.sh list                         # 무엇이 링크됐는지 확인
./install.sh uninstall                    # 우리 심링크만 제거 (남의 파일 안 건드림)
```

멱등적이라 **재실행 = 동기화**입니다(새 구성요소 반영, 기존 링크는 `already`로 skip). 유형·이름으로 골라 적용할 수도 있습니다:

```bash
./install.sh install --type agents            # agents 전체만
./install.sh install debugger nestjs commit   # 개별 구성요소만 (확장자 없이)
```

기존에 실제 파일/다른 링크가 있으면 `CONFLICT`로 건너뛰며, 덮어쓰려면 `--force`(기존은 `.bak`로 백업)를 붙입니다.
