# my-harness

**새 백엔드 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code용 커스텀 에이전트·스킬·커맨드를 한곳에서 작성·검증·관리합니다. 범위는 **백엔드**(REST API·서비스·영속성)이며, 특정 프로젝트·스택에 종속되지 않은 **재사용 기본값**입니다. 새 프로젝트에는 `install.sh`로 심링크해 씁니다.

## 구조

```
my-harness/
├── agents/
├── skills/
├── commands/
├── prompts/
├── install.sh
└── CLAUDE.md
```

## 구성요소

| 유형 | 구성요소 |
| --- | --- |
| **agents** | `feature-pm` · `backend-designer` · `backend-impl` · `boundary-verifier` · `test-writer` · `architecture-expert` · `debugger` · `performance-optimizer` · `security-auditor` · `code-reviewer` |
| **skills** | `spring-boot` · `nestjs` |
| **commands** | `commit` · `push` |
| **prompts** | `feature-development` |

> 상세 사용법은 각 파일의 frontmatter `description`을 참고하세요.

## 오케스트레이션

### 기능 개발

설계부터 구현·테스트까지 진행하는 프롬프트는 [`prompts/feature-development.md`](prompts/feature-development.md)에 있습니다. 복사해서 `{빈칸}`만 채워 쓰세요.

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
