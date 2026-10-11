# mods/

Claude Code 모드(function hooks 플러그인)를 두는 곳입니다. 폴더 하나가 모드 하나이며, `./install.sh install --type mods [이름]`이 폴더 경로를 `~/.claude/settings.json`의 `env.CLAUDE_CODE_PLUGIN_DIRS`에 등록합니다. 전역 설치만 지원하고, 새 세션부터 로드됩니다. 코드 수정은 다음 세션부터 반영됩니다.

```
mods/<모드 이름>/
  .claude-plugin/plugin.json   # 매니페스트 (name·version·description·types)
  hooks/hooks.json             # { "modules": ["./register.tsx"] }
  hooks/register.tsx           # register(on) — 훅 모듈
  types/index.d.ts             # $.state 값 계약
  tests/*.test.tsx             # claude plugin test <폴더>
```

검증:

```bash
claude plugin validate mods/<이름>
```

```bash
claude plugin test mods/<이름>
```

## next-steps

턴이 끝나면 대화를 바탕으로 다음에 할 일을 최대 4개 제안하고, 입력창 위 밴드에 체크박스로 띄웁니다.

- 숫자 `1`~`4`: 항목 선택/해제 (입력창이 비어 있을 때)
- `0` 또는 **입력창에 채우기**: 고른 항목을 입력창에 넣는다. 여러 개면 번호 목록 하나로 합친다
- **닫기**, 또는 다음 프롬프트 전송: 밴드가 사라진다
- `/next-steps`: 지금 바로 제안 · `/next-steps on|off`: 자동 제안 켜기/끄기 (세션 간 유지)
- `/next-steps usage`: 제안 생성에 쓴 토큰(마지막 호출, 이 세션 누적)

제안은 `$.model.fork`로 만듭니다. 메인 대화를 프롬프트 캐시째 재사용하는 별도 호출이라 답변 본문에는 섞이지 않습니다. 서브에이전트 턴과 중단·오류로 끝난 턴에는 제안하지 않습니다.
