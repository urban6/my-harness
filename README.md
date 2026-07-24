# my-harness

**새 프로젝트를 시작할 때 가져다 쓰는 Claude 기본 셋팅(부트스트랩 템플릿)**

Claude Code에서 쓸 커스텀 서브에이전트·스킬·슬래시 커맨드를 한곳에서 작성·검증·버전 관리하는 저장소입니다. 여기 자산은 특정 프로젝트에 종속되지 않은 **재사용 기본값**이며, 새 프로젝트를 열면 전역 `~/.claude` 또는 그 프로젝트의 `.claude/`로 심링크/복사해 적용합니다. **작성 → 검증 → 새 프로젝트에 적용**의 순환으로 굴러갑니다.

---

## 디렉터리 구조

```
my-harness/
├── README.md          # 이 문서
├── CLAUDE.md          # 이 레포 작업 시 Claude에게 주는 프로젝트 지침
├── .gitignore         # OS/에디터 잡파일 무시
├── agents/            # 커스텀 서브에이전트 정의 (.md + frontmatter)
├── skills/            # 커스텀 스킬 (SKILL.md 등)
└── commands/          # 슬래시 커맨드 (.md)
```

| 디렉터리 | 용도 |
| --- | --- |
| `agents/` | 특정 작업을 위임할 수 있는 서브에이전트 정의. 파일 하나가 에이전트 하나. |
| `skills/` | 재사용 가능한 작업 절차/지침 묶음. Claude가 필요 시 로드해 따르는 스킬. |
| `commands/` | `/이름` 형태로 호출하는 슬래시 커맨드. |

---

## 현재 포함된 자산

기본 셋팅이 제공하는 자산 카탈로그입니다. (상세는 각 디렉터리와 파일 frontmatter에서 확인)

| 유형 | 자산 |
| --- | --- |
| **agents (풀스택 기능 팀)** | `feature-pm` — 기능 분해·Phase 할당·팀 조율 · **설계** `api-designer`·`ui-designer`·`db-migrator` · **구현** `backend-impl`·`frontend-impl` · **검증** `boundary-verifier` · **테스트** `test-suite` |
| **agents (진단 전문가)** | `architecture-expert` — 아키텍처 진단·ADR · `debugger` — 버그 재현·근본 원인 규명 · `performance-optimizer` — 성능 병목 진단 |
| **skills** | `nestjs` · `spring-boot` — 백엔드 관용 패턴 참조 |
| **commands** | `commit` · `push` — git 커맨드 |

> 기능 팀은 전역 `~/.claude`에 이미 있는 `code-reviewer`·`security-auditor`·`test-writer`를 함께 재사용합니다. 이 저장소에서 중복 정의하지 않습니다.

---

## 자산 유형별 설명

### 에이전트 (`agents/`)

특정 역할에 특화된 서브에이전트입니다. `.md` 파일에 frontmatter로 메타데이터를, 본문에 시스템 프롬프트를 작성합니다.

```markdown
---
name: code-reviewer
description: 코드 작성/수정 후 품질·보안·유지보수성을 리뷰한다. 코드 변경 시 사용.
tools: Read, Grep, Glob, Bash
---

당신은 시니어 코드 리뷰어입니다. 다음 기준으로 변경 사항을 검토하세요...
```

- `name`: kebab-case 식별자
- `description`: 언제 이 에이전트를 쓰는지 (자동 위임 판단에 사용)
- `tools`: 허용 도구 목록 (생략 시 전체 상속)

### 스킬 (`skills/`)

특정 작업을 수행하는 절차와 규칙을 담은 묶음입니다. 스킬별 폴더 안에 `SKILL.md`와 참고 자료를 둡니다.

```
skills/
└── my-skill/
    ├── SKILL.md        # 트리거 조건 + 수행 절차
    └── references/     # 보조 문서·템플릿 (선택)
```

`SKILL.md` frontmatter의 `description`에 **언제 발동하는지(트리거)**를 명확히 적는 것이 중요합니다.

### 커맨드 (`commands/`)

`/이름`으로 호출하는 슬래시 커맨드입니다. `commands/deploy.md` → `/deploy`.

---

## 새 프로젝트에 적용하기

이 템플릿의 자산을 실제 Claude Code에 반영하는 방법입니다. **심링크**를 쓰면 이 레포만 수정해도 연결된 모든 곳에 즉시 반영됩니다.

### 전역 적용 (모든 프로젝트)

`~/.claude`에 연결하면 어느 프로젝트에서든 쓸 수 있습니다.

```bash
# 개별 에이전트를 심링크
ln -s ~/SideProjects/my-harness/agents/api-designer.md ~/.claude/agents/api-designer.md

# 스킬 폴더를 통째로 심링크
ln -s ~/SideProjects/my-harness/skills/my-skill ~/.claude/skills/my-skill

# 커맨드 심링크
ln -s ~/SideProjects/my-harness/commands/deploy.md ~/.claude/commands/deploy.md

# 에이전트 전체를 한 번에
for f in ~/SideProjects/my-harness/agents/*.md; do ln -s "$f" ~/.claude/agents/; done
```

### 프로젝트별 적용 (그 프로젝트에서만)

특정 새 프로젝트에서만 쓰고 싶으면 전역 `~/.claude` 대신 그 프로젝트의 `.claude/` 아래로 연결합니다.

```bash
# 새 프로젝트 루트에서
mkdir -p .claude/agents
ln -s ~/SideProjects/my-harness/agents/backend-impl.md .claude/agents/backend-impl.md
```

> 심링크 대신 복사(`cp`)를 쓰면 스냅샷처럼 고정되지만, 수정할 때마다 다시 복사해야 합니다. 템플릿을 계속 개선하며 재사용하려면 심링크를 권장합니다.

---

## 새 자산 추가하기

1. 해당 디렉터리에 파일/폴더를 만든다 (`agents/`, `skills/`, `commands/`).
2. frontmatter의 `name`, `description`을 채운다 — 특히 `description`은 **언제 쓰는지**를 명확히.
3. `~/.claude`로 심링크해서 실제로 동작하는지 확인한다.
4. 커밋한다.

---

## 컨벤션

- **네이밍**: kebab-case (`security-auditor`, `deep-research`).
- **description 우선**: 자동 위임/트리거 정확도는 `description` 품질에 좌우됩니다. "무엇을 하는지"보다 "언제 쓰는지"를 적으세요.
- **커밋 단위**: 자산 하나 = 커밋 하나를 기본으로, 관련 변경은 함께 묶습니다.
- **최소 권한**: 에이전트 `tools`는 실제로 필요한 것만 나열합니다.
