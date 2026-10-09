# my-harness

**새 백엔드 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code에서 쓸 커스텀 에이전트, 스킬, 커맨드를 이 저장소 한곳에서 만들고 검증하고 관리합니다. 다루는 범위는 백엔드(REST API, 서비스, 영속성)입니다. 특정 프로젝트나 스택에 묶이지 않아서 어느 프로젝트에서든 기본값으로 다시 쓸 수 있습니다. 새 프로젝트에는 `install.sh`로 심링크를 걸어 씁니다.

## 구조

```
my-harness/
├── agents/
├── skills/
├── commands/
├── prompts/
├── docs/
├── install.sh
└── CLAUDE.md
```

## 구성요소

| 유형 | 구성요소 |
| --- | --- |
| **agents** | `feature-pm` · `backend-designer` · `backend-impl` · `boundary-verifier` · `test-writer` · `architecture-expert` · `debugger` · `performance-optimizer` · `security-auditor` · `code-reviewer` |
| **skills** | `spring-boot` · `nestjs` |
| **commands** | `commit` · `push` |
| **prompts** | `feature-development` · `code-review` |
| **docs** | `superpowers` |

> 자세한 사용법은 각 파일 frontmatter의 `description`에 적혀 있습니다.

## 외부 플러그인

[superpowers](https://github.com/obra/superpowers)는 하네스와 따로 두고 절차 스킬로만 씁니다. 어떤 원칙으로 쓰는지, 상황마다 어떤 스킬을 고르는지, 하네스 자산과는 어디서 선을 긋는지는 [`docs/superpowers.md`](docs/superpowers.md)에 정리해 두었습니다.

## 오케스트레이션

### 기능 개발

설계에서 구현, 테스트까지 진행하는 프롬프트는 [`prompts/feature-development.md`](prompts/feature-development.md)에 있습니다. 복사한 뒤 `{빈칸}`만 채우면 됩니다.

### 코드 리뷰

커밋하거나 배포하기 전에 코드를 여러 관점에서 점검하는 프롬프트는 [`prompts/code-review.md`](prompts/code-review.md)에 있습니다. 이것도 복사한 뒤 `{빈칸}`만 채우면 됩니다.

### 디버깅 · 성능 개선

```text
{증상 — 무엇이 언제부터 어떻게}. 순서대로 진행해줘.

1) debugger로 재현 경로를 잡고 문제 구간을 file:line으로 특정
2) performance-optimizer로 {관찰 지점}을 포함해 병목을 우선순위로 진단
3) architecture-expert 관점에서 구조적 개선안을 트레이드오프로 비교

셋 다 진단만 하고 코드는 고치지 마. 마지막에 안 A/B/C로 정리해줘.
```

## 적용 방법

```bash
./install.sh install                          # 전역 (~/.claude, 기본값)
./install.sh install --project [경로]         # 프로젝트별 (.claude/, 생략 시 현재 디렉터리)
./install.sh install --type agents            # 유형만
./install.sh install debugger nestjs commit   # 개별 구성요소만 (확장자 없이)
./install.sh install --dry-run                # 변경 없이 수행 예정만 출력
./install.sh list                             # 무엇이 링크됐는지 확인
./install.sh uninstall                        # 우리 심링크만 제거 (남의 파일 안 건드림)
```
