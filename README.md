# my-harness

**새 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code용 커스텀 에이전트·스킬·커맨드를 한곳에서 작성·검증·관리합니다. 특정 프로젝트에 종속되지 않은 **재사용 기본값**이며, 새 프로젝트에는 `install.sh`로 심링크해 씁니다.

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

에이전트는 프롬프트에서 **이름으로 지목**해야 걸립니다 — 자동 트리거는 신뢰도가 낮아 두루뭉술하게 요청하면 메인 에이전트가 혼자 처리합니다. 팀은 **메인 세션에서 시작**해야 합니다(서브에이전트는 추가 스폰이 막혀 있음).

프롬프트에 넣을 다섯 조각 — **범위·스택** · **에이전트 이름** · **번호 매긴 단계** · **산출물 형태**(`file:line 근거로`, `PASS/FIX/REDO 판정만`, `안 A/B/C로 비교`) · **검증 명령**. 아래 템플릿의 `{ }`는 채워 넣을 자리입니다.

### 1. 기능 개발

풀스택 기능을 통째로 맡길 땐 A, 범위가 좁거나 특정 워커만 필요하면 B.

**A. 오케스트레이터 위임** — `feature-pm`이 설계(`api-designer`·`ui-designer`·`db-migrator`) → 구현·검증(`backend-impl`·`frontend-impl`·`boundary-verifier`) → 테스트(`test-suite`)를 Phase 0~4로 스폰·조율하고, 산출물은 `_workspace/features/{기능명}/`에 쌓입니다.

```text
feature-pm 에이전트로 {기능명} 기능을 풀스택으로 개발해줘.
스택은 {스택}이고, 범위는 {포함 / 제외}야.
요구 분해 → 설계 → 구현 → 검증 → 통합까지 Phase 파이프라인으로 진행하고,
각 Phase 산출물 경로와 마지막 통합 요약을 알려줘.
```

**B. 파이프라인 직접 엮기**

```text
{기능명} 기능을 설계부터 구현까지 진행해줘.
스택은 {스택}, {범위}야 — {불필요한 워커}는 빼줘.

1) api-designer로 엔드포인트·요청/응답 스키마·에러 응답을 문서로 먼저 정리
2) db-migrator로 테이블·인덱스·제약. 단 {워커 기본 스택} 말고 {실제 스택} 기준으로.
3) 그 두 산출물을 입력으로 {구현 스킬 또는 워커}로 구현
4) architecture-expert로 {레이어·의존성 규칙}이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 {테스트 명령} 결과를 그대로 보여줘.
```

> 2번의 `단, ~ 말고 ~ 기준으로`가 핵심입니다. `backend-impl`·`db-migrator`·`test-suite`는 Next.js·Drizzle·Vitest·MSW를 전제로 쓰여 있어, 덮어쓰지 않으면 다른 스택에서 엉뚱한 산출물이 나옵니다.

### 2. 기존 코드 리뷰 · 점검

```text
{대상 범위 — 경로나 최근 변경}를 {커밋 전 / 배포 전}에 점검해줘.

1) architecture-expert로 {레이어·의존성 규칙}이 깨진 곳,
   {경계 밖으로 새면 안 되는 타입}이 노출된 곳을 찾아줘.
2) {스킬명} 스킬의 references/{문서}.md 체크리스트로 {점검 항목}을 항목별로.
3) boundary-verifier로 {산출물 A}와 {산출물 B}가 일치하는지 교차 검증.
   판정(PASS/FIX/REDO)만 하고 수정은 하지 마 — 고칠지는 내가 정할게.

발견 항목은 file:line 근거와 함께 심각도 순으로 정리해줘.
```

> 스킬의 참조 문서는 **경로로 직접 지목**해야 안정적으로 걸립니다.

### 3. 디버깅 · 성능 개선

```text
{증상 — 무엇이 언제부터 어떻게}. 순서대로 진행해줘.

1) debugger로 재현 경로를 잡고 문제 구간을 file:line으로 특정
2) performance-optimizer로 {관찰 지점}을 포함해 병목을 우선순위로 진단
3) architecture-expert 관점에서 구조적 개선안을 트레이드오프로 비교

셋 다 진단만 하고 코드는 고치지 마. 마지막에 안 A/B/C로 정리해줘.
```

> 진단 셋(`debugger`·`performance-optimizer`·`architecture-expert`)은 읽기 전용이라 순서대로 엮기 좋습니다. `{관찰 지점}`에 스택 특유의 단서(ORM fetch 전략, 실제 쿼리 로그, 리렌더 경로)를 심으면 진단 품질이 올라갑니다.

## 예시 — 템플릿 1-B를 Spring Boot에 채우면

스킬은 스택 신호(`build.gradle`의 `org.springframework.boot`, `@RestController`)로 **자동 발동**하지만 에이전트는 이름으로 지목해야 합니다. 기능 하나 정도면 에이전트 없이 스킬 단독으로도 충분합니다.

```text
주문(Order) API를 설계부터 구현까지 진행해줘.
스택은 Spring Boot 3.x + JPA + PostgreSQL, 백엔드 전용이야 — 프런트 워커는 빼줘.

1) api-designer로 엔드포인트·요청/응답 스키마·에러 응답(RFC 9457)을 문서로 먼저 정리
2) db-migrator로 테이블·인덱스·제약. 단 Drizzle 말고 JPA 엔티티 + Flyway SQL 기준으로.
3) 그 두 산출물을 입력으로 spring-boot 스킬을 따라 컨트롤러·서비스·리포지토리 구현
4) architecture-expert로 web → service → repository 단방향이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 ./gradlew test 결과를 그대로 보여줘.
```

> 프런트 워커까지 스폰하는 `feature-pm`은 백엔드 전용 작업에 과합니다 — 파이프라인을 직접 엮는 편이 낫습니다.

## 적용 방법

`install.sh`가 구성요소를 사용처로 **심링크**합니다 — 이 레포만 고치면 연결된 모든 곳에 반영됩니다. 멱등적이라 **재실행 = 동기화**입니다.

```bash
./install.sh install                          # 전역 (~/.claude, 기본값)
./install.sh install --project [경로]         # 프로젝트별 (.claude/, 생략 시 현재 디렉터리)
./install.sh install --type agents            # 유형만
./install.sh install debugger nestjs commit   # 개별 구성요소만 (확장자 없이)
./install.sh install --dry-run                # 변경 없이 수행 예정만 출력
./install.sh list                             # 무엇이 링크됐는지 확인
./install.sh uninstall                        # 우리 심링크만 제거 (남의 파일 안 건드림)
```

구성요소가 아닌 것(안내용 `README.md`, `*-workspace/` 평가 부산물)은 자동 제외됩니다. 기존 파일·타 링크가 있으면 `CONFLICT`로 건너뛰고, `--force`를 붙이면 `.bak`로 백업한 뒤 교체합니다.
