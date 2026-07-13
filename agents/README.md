# agents/

커스텀 서브에이전트 정의를 두는 곳입니다. `.md` 파일 하나가 에이전트 하나이며, frontmatter에 `name`·`description`·`tools`를, 본문에 시스템 프롬프트를 작성합니다.

```markdown
---
name: my-agent
description: 언제 이 에이전트를 사용하는지.
tools: Read, Grep, Glob, Bash
---

시스템 프롬프트...
```
