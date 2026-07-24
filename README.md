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
