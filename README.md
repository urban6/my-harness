# my-harness

**새 백엔드 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code용 커스텀 에이전트·스킬·커맨드를 한곳에서 작성·검증·관리합니다. 범위는 **백엔드**(REST API·서비스·영속성)이며, 특정 프로젝트·스택에 종속되지 않은 **재사용 기본값**입니다. 새 프로젝트에는 `install.sh`로 심링크해 씁니다.

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
| **agents** | `feature-pm` · `backend-designer` · `backend-impl` · `boundary-verifier` · `test-writer` · `architecture-expert` · `debugger` · `performance-optimizer` · `security-auditor` · `code-reviewer` |
| **skills** | `spring-boot` · `nestjs` |
| **commands** | `commit` · `push` |

> 상세 사용법은 각 파일의 frontmatter `description`을 참고하세요.

## 오케스트레이션

에이전트·스킬은 프롬프트에서 **이름으로 지목**해야 확실히 걸립니다 — 두루뭉술하게 요청하면 메인 에이전트가 혼자 처리합니다. `feature-pm`은 **메인 세션에서 직접 호출**하세요(서브에이전트는 추가 스폰이 막혀 있습니다).

아래 템플릿의 `{ }`는 채워 넣을 자리입니다.

### 기능 개발 — 통째로 위임

```text
feature-pm 에이전트로 {기능명} 기능을 개발해줘.
스택은 {스택}이고, 범위는 {포함 / 제외}야.

Phase 0에서 스택을 확정해 모든 워커에 주입하고,
설계(backend-designer) → 구현·검증(backend-impl·boundary-verifier) → 테스트(test-writer) 순으로 진행해줘.
각 Phase 산출물 경로와 마지막에 {테스트 명령} 결과를 그대로 보여줘.
```

산출물은 `_workspace/features/{기능명}/`에 `00_requirements.json` → `01_api_design.md`·`02_db_design.md` → `03_integration_summary.md` 순으로 쌓입니다.

### 기능 개발 — 직접 엮기

워커 조합·순서를 손으로 통제하고 싶을 때. PM을 거치지 않으므로 **스택을 프롬프트에 명시**하세요.

```text
{기능명} 기능을 설계부터 구현까지 진행해줘.
스택은 {스택}, {범위}야.

1) backend-designer로 엔드포인트·요청/응답 스키마·에러 응답과
   테이블·인덱스·제약을 한 번에 정리. 정합 요약표까지 남겨줘.
2) 그 산출물을 입력으로 {구현 스킬 또는 backend-impl}로 구현
3) architecture-expert로 {레이어·의존성 규칙}이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 {테스트 명령} 결과를 그대로 보여줘.
```

### 코드 리뷰 · 점검

```text
{대상 범위 — 경로나 최근 변경}를 {커밋 전 / 배포 전}에 점검해줘.

1) architecture-expert로 {레이어·의존성 규칙}이 깨진 곳,
   {경계 밖으로 새면 안 되는 타입}이 노출된 곳을 찾아줘.
2) security-auditor로 인증·인가·민감 데이터 노출을 심각도와 함께 점검.
3) {스킬명} 스킬의 references/{문서}.md 체크리스트로 {점검 항목}을 항목별로.
4) code-reviewer로 가독성·네이밍·에러 핸들링·중복을 마무리 점검.

넷 다 진단만 하고 수정은 하지 마 — 고칠지는 내가 정할게.
발견 항목은 file:line 근거와 함께 심각도 순으로 정리해줘.
```

### 디버깅 · 성능 개선

```text
{증상 — 무엇이 언제부터 어떻게}. 순서대로 진행해줘.

1) debugger로 재현 경로를 잡고 문제 구간을 file:line으로 특정
2) performance-optimizer로 {관찰 지점}을 포함해 병목을 우선순위로 진단
3) architecture-expert 관점에서 구조적 개선안을 트레이드오프로 비교

셋 다 진단만 하고 코드는 고치지 마. 마지막에 안 A/B/C로 정리해줘.
```

### 채워 넣은 예시 — Spring Boot

```text
주문(Order) API를 설계부터 구현까지 진행해줘.
스택은 Spring Boot 3.x + JPA + PostgreSQL이야.

1) backend-designer로 엔드포인트·요청/응답 스키마·에러 응답(RFC 9457)과
   테이블·인덱스·제약을 한 번에 정리. JPA 엔티티 + Flyway SQL 기준으로.
2) 그 산출물을 입력으로 spring-boot 스킬을 따라 컨트롤러·서비스·리포지토리 구현
3) architecture-expert로 web → service → repository 단방향이 지켜졌는지 점검

각 단계 산출물 경로를 알려주고, 마지막에 ./gradlew test 결과를 그대로 보여줘.
```

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
