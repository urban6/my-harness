# plugins/

외부 플러그인 목록을 두는 곳입니다. 파일 하나가 플러그인 하나이며, `./install.sh install --type plugins [이름]`이 `claude plugin install`로 설치합니다. 전역은 user 스코프, `--project`면 그 프로젝트의 local 스코프에 설치되고, 새 세션부터 로드됩니다.

```json
{ "id": "<플러그인>@<마켓플레이스>", "marketplace": "<owner/repo>" }
```

- `marketplace`는 공식 마켓플레이스(`claude-plugins-official`)가 아닐 때만 적는다. 없으면 먼저 user 스코프에 추가한다.
- 스킬·에이전트를 `skills/`·`agents/`에 복사하지 않는다. 사용법은 `docs/<이름>.md`에 둔다.

- `superpowers` → 절차 스킬 ([docs/superpowers.md](../docs/superpowers.md))
- `claude-md-management` → CLAUDE.md 점검·갱신 ([docs/claude-md-management.md](../docs/claude-md-management.md))
- `codex` → Codex 교차 검증 ([docs/codex.md](../docs/codex.md))
