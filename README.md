# my-harness

새 백엔드 프로젝트를 시작할 때 가져다 쓰는 Claude Code 기본 세팅입니다.

에이전트, 스킬, 커맨드를 이 저장소에서 만들고 검증한 뒤, `install.sh`로 전역(`~/.claude`)이나 프로젝트(`.claude/`)에 심링크를 걸어 씁니다. 다루는 범위는 백엔드(REST API, 서비스, 영속성)이고, 특정 프로젝트나 스택에 묶이지 않게 작성합니다.

## 설치

```bash
./install.sh install   # 전체 자산을 ~/.claude에 링크
./install.sh list      # 링크된 항목 확인
```

프로젝트에만 넣거나 일부만 고르는 방법은 [설치 옵션](#설치-옵션)에 있습니다.

## 구조

```
my-harness/
├── agents/      # 서브에이전트
├── skills/      # 스택별 구현 패턴
├── commands/    # 슬래시 커맨드
├── prompts/     # 복사해 쓰는 프롬프트 (설치 대상 아님)
├── docs/        # 외부 플러그인 사용법 (설치 대상 아님)
├── install.sh
└── CLAUDE.md
```

## 에이전트

**기능 개발** — `feature-pm`이 나머지 넷을 순서대로 부립니다.

| 이름 | 하는 일 |
| --- | --- |
| `feature-pm` | 요구사항 분해, 스택 확정, 워커 조율 (메인 세션에서 직접 호출) |
| `backend-designer` | API와 DB 스키마를 함께 설계 |
| `backend-impl` | 설계를 코드로 구현 |
| `boundary-verifier` | 설계와 구현이 서로 맞는지 검증 |
| `test-writer` | 단위·통합 테스트 작성 |

**진단** — 코드를 직접 고치지 않고 원인과 개선안만 알려 줍니다.

| 이름 | 하는 일 |
| --- | --- |
| `architecture-expert` | 구조·의존성 진단, 기술 선택 비교 |
| `code-reviewer` | 가독성, 네이밍, 에러 처리, 중복 리뷰 |
| `debugger` | 버그 재현, 근본 원인 특정 |
| `performance-optimizer` | 병목, N+1, 복잡도 진단 |
| `security-auditor` | 인증·인가, 인젝션 등 보안 점검 |

## 스킬

| 이름 | 하는 일 |
| --- | --- |
| `spring-boot` | Spring Boot 3.x 구현 패턴 |
| `nestjs` | NestJS 구현 패턴 |
| `screen-spec-analyzer` | 화면 캡처나 화면정의서에서 API 응답 필드 추출 |

## 커맨드

| 이름 | 하는 일 |
| --- | --- |
| `/commit` | 변경을 논리 단위로 나눠 커밋 |
| `/push` | 보호 브랜치와 강제 푸시를 확인하고 푸시 |
| `/pre-pr` | 테스트·린트·진단을 거쳐 PR 생성 |
| `/post-pr` | PR의 CI 결과와 리뷰 코멘트 대응, 머지 후 브랜치 정리 |

## 프롬프트 예시

프롬프트를 복사해 `{빈칸}`만 채워 씁니다.

| 상황 | 프롬프트 |
| --- | --- |
| 기능 개발 (설계 → 구현 → 테스트) | [`prompts/feature-development.md`](prompts/feature-development.md) |
| 커밋·배포 전 코드 리뷰 | [`prompts/code-review.md`](prompts/code-review.md) |
| 화면 캡처로 API 응답 설계 | [`prompts/screen-spec.md`](prompts/screen-spec.md) |

### 디버깅 · 성능 개선

```text
{증상 — 무엇이 언제부터 어떻게}. 순서대로 진행해줘.

1) debugger로 재현 경로를 잡고 문제 구간을 file:line으로 특정
2) performance-optimizer로 {관찰 지점}을 포함해 병목을 우선순위로 진단
3) architecture-expert 관점에서 구조적 개선안을 트레이드오프로 비교

셋 다 진단만 하고 코드는 고치지 마. 마지막에 안 A/B/C로 정리해줘.
```

## 외부 플러그인

- [superpowers](docs/superpowers.md)
- [ponytail](docs/ponytail.md)

## 설치 옵션

```bash
./install.sh install                          # 전역 (~/.claude, 기본값)
./install.sh install --project [경로]         # 프로젝트별 (.claude/, 생략 시 현재 디렉터리)
./install.sh install --type agents            # 유형만
./install.sh install debugger nestjs commit   # 개별 구성요소만 (확장자 없이)
./install.sh install --dry-run                # 변경 없이 수행 예정만 출력
./install.sh install --force                  # 기존 파일은 .bak으로 백업하고 교체
./install.sh list                             # 무엇이 링크됐는지 확인
./install.sh uninstall                        # 우리 심링크만 제거 (남의 파일 안 건드림)
```
