# rules/

규칙 파일을 두는 곳입니다. `.md` 파일 하나가 규칙 하나이며, Claude Code가 `~/.claude/rules/` 또는 프로젝트 `.claude/rules/`에서 읽어 지침으로 적용합니다.

`rules/api-error-format.md` → 에러 응답 형식 규칙

특정 경로에만 적용하려면 frontmatter의 `paths`에 glob을 적습니다.
