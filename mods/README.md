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

턴이 끝나면 대화를 바탕으로 다음에 할 일을 최대 4개(설정 가능) 제안하고, 입력창 위 밴드에 체크박스로 띄웁니다.

- 숫자 `1`~`9`: 항목 선택/해제 (입력창이 비어 있을 때)
- `0` 또는 **입력창에 채우기**: 고른 항목을 입력창에 넣는다. 여러 개면 번호 목록 하나로 합친다
- **닫기**, 또는 다음 프롬프트 전송: 밴드가 사라진다
- `/next-steps`: 지금 바로 제안 · `/next-steps on|off`: 자동 제안 켜기/끄기 (세션 간 유지)
- `/next-steps usage`: 제안 생성에 쓴 토큰(마지막 호출, 이 세션 누적)
- `/next-steps model haiku|fork`: 제안을 만들 모델 (기본 `haiku`, 세션 간 유지)
  - `haiku`: 최근 대화(약 24,000자)만 Haiku에 보낸다. 싸고, 대화가 길어져도 비용이 일정
  - `fork`: 대화 전체를 세션 모델에 보낸다. 맥락을 다 보지만 대화가 길수록 비싸다
- `/next-steps limit <토큰>`: `fork`에서 컨텍스트가 이 크기를 넘으면 자동 제안을 건너뛴다 (기본 100,000, `0`은 끔). 직접 요청한 `/next-steps`는 건너뛰지 않는다
- 목록 맨 아래에 이번 제안에 쓴 토큰(입력·캐시 읽기·출력)과 모델이 흐리게 표시된다
- `/next-steps ui`: 화면 설정 보기 · `/next-steps ui <항목> <값>`으로 바꾸기 (세션 간 유지)
  - `count 1~9`: 항목 수 (기본 4, 다음 제안부터) · `bold on|off`: 항목 굵게 (기본 on)
  - `gap 0~2`: 항목 사이 빈 줄 (기본 1) · `tokens on|off`: 토큰 줄 표시 (기본 on)
  - 글자 크기는 모드가 정할 수 없다. 앱 확대(`Cmd` `+`)나 터미널 글꼴 크기로 바꾼다

제안은 답변과 별도의 호출로 만들어서 답변 본문에는 섞이지 않습니다. 서브에이전트 턴과 중단·오류로 끝난 턴에는 제안하지 않습니다.

## context-bar

입력창 위 밴드에 컨텍스트 창 사용량을 카테고리별 색 막대로 늘 보여줍니다.

```
███████████████████░░░░░░·····  62% · 압축까지 43k · Messages 104k · +6k
```

- `█` 구간은 카테고리마다 `/context`와 같은 색으로 칠한다. `░`은 남은 공간, 막대 끝의 `·`는 auto-compact 버퍼
- 칸은 토큰 비율대로 나눈다. 아주 작은 카테고리는 칸을 못 받을 수 있지만, 쓰고 있는 게 있으면 최소 1칸은 보인다
- 비율 숫자는 전체 창 대비
- `압축까지`: 자동 압축까지 남은 토큰. 자동 압축이 꺼져 있으면 `124k / 200k`(사용량 / 창)
- 가장 큰 카테고리 이름을 막대와 같은 색으로 써서 범례로 쓴다
- `+6k`: 직전 턴 대비 증가량. 첫 측정과 압축 직후에는 없다
- 화면이 좁으면 증가량, 카테고리 이름 순으로 뺀다
- 경고 색은 자동 압축 시점 대비 70% 이상 노랑, 85% 이상 빨강(압축이 꺼져 있으면 창 대비)
- 메인 턴이 끝날 때마다 갱신한다. 압축 직후와 첫 응답 전에는 `컨텍스트 측정 전`
- 로컬 추정(`summary`)이라 비용이 없지만 `/context`의 값과 조금 다를 수 있다
- `/context-bar`: 현재 설정, 마지막 측정값, 카테고리별 구성
- `/context-bar on|off`: 켜기/끄기 (세션 간 유지)
- `/context-bar warn <노랑> <빨강>`: 경고 기준 (예: `warn 70 85`, 세션 간 유지)
- next-steps 제안이 떠 있으면 그 아래, 입력창 바로 위에 그린다
