# context-bar 설계

컨텍스트 창을 얼마나 쓰고 있는지 입력창 위 밴드에 카테고리별 색 막대로 늘 보여주는 모드.

## 목적과 범위

- **목적**: 지금 컨텍스트 창 사용량을 한눈에 확인한다. `/context`를 따로 열지 않아도 된다.
- **성공 기준**
  - 메인 턴이 끝날 때마다 막대와 비율이 갱신된다.
  - 막대 색 구간이 `/context`의 카테고리 색과 같다.
  - 갱신에 API 호출 비용이 들지 않는다.
- **범위 밖**: 범례, 카테고리별 토큰 목록, 정확한 `full` 계산, 토스트 경고, 응답 중 실시간 갱신, 서브에이전트 표시.

## 화면

입력창 바로 위에 한 줄로 그린다. next-steps 제안이 떠 있으면 그 아래, 입력창 바로 위에 온다.

```
███████████████████░░░░░░·····  62% · 124k / 200k
```

- **막대**: 폭은 `bodyColumns - 24`를 10~30칸으로 묶은 값(넓은 화면에서 30칸, 좁으면 줄어듦). `used` 칸은 `█` 문자에 카테고리 색을 입힌다.
- **구간**: breakdown의 `categories`를 순서대로 나열한다.
  - `used`: 해당 카테고리의 `color`(테마 키)로 칠한다.
  - `free`: `inactive` 색의 `░`.
  - `buffer`: 흐린(`dimColor`) `·`. auto-compact 지점이 어디인지 보이게 한다.
  - `deferred`: 창 밖이므로 그리지 않는다.
- **비율**: `breakdown.percentage`. `/context`와 같은 기준인 `rawMaxTokens` 대비다. 보통은 모델 창 전체이고, 압축 창을 따로 설정했다면 그 크기다.
  - `warn.yellow` 이상이면 `warning` 색, `warn.red` 이상이면 `error` 색, 그 외는 기본 색.
- **토큰**: `totalTokens / rawMaxTokens`를 `124k`, `1.2M` 형태로 표시한다.
- **측정 전**(값이 없음): `컨텍스트 측정 전`을 흐리게 표시한다.
- **꺼짐**: 아무것도 그리지 않고 `next(e)`만 반환한다.

## 동작

| 이벤트 | 처리 |
|---|---|
| `session.start` | 저장된 `enabled`·`warn`을 `$.store`에서 읽어 상태에 반영. `snapshot`은 `null`로 초기화(프로세스 시작·모드 다시 로드). `/context-bar` 명령 등록 |
| `session.end` | `reason`이 `clear`·`resume`이면 `snapshot`을 `null`로. `/clear`·resume에는 `session.start`가 오지 않기 때문 |
| `session.measure` | 켜져 있고 `e.changed`에 `context`가 있으면(이 이벤트는 메인 턴 뒤에만 오므로 서브에이전트는 따로 거르지 않는다) `$.session.usage({ breakdown: 'summary' })`를 호출. breakdown이 있으면 `toSnapshot`으로 줄여 `snapshot`에 저장 |
| `session.compact` | `next(e)` 이후, 메인 대화(`agentId` 없음)이고 `precompute`가 아니며 결과가 `skip`이 아니면 `snapshot`을 `null`로 만든다. 압축 후 첫 응답 전까지 측정값이 없기 때문 |
| `ui.render` `AbovePrompt` | `next(e)` 결과를 위에, context-bar를 아래에 세로로 쌓아 반환. 꺼졌거나 설문이 떠 있으면(`hasSurvey`) `next(e)` 그대로 |
| `command.run` `context-bar` | 아래 명령 처리 |

- 렌더 훅은 상태만 읽는다. 계산은 `session.measure`에서만 한다.
- 응답하는 동안(`isWorking`)에도 마지막 값을 그대로 보여준다.
- `session.measure`는 rate-limit·비용 변화로도 온다. `changed`에 `context`가 없으면 다시 계산하지 않는다.
- `usage()` 호출이 실패하면 기존 `snapshot`을 유지하고 `$.ui.log`에 남긴다. 밴드는 깨지지 않는다.

## 명령

- `/context-bar on|off`: 밴드 켜기/끄기. `$.store`에 저장해 세션 간 유지. 기본 `on`. 끌 때 `snapshot`을 비워, 다시 켜면 다음 턴까지 `컨텍스트 측정 전`을 보여준다(꺼진 동안의 오래된 값을 보여주지 않기 위해).
- `/context-bar warn <노랑> <빨강>`: 경고 기준(%). 정수이고 `0 < 노랑 < 빨강 ≤ 100`이어야 하며, 아니면 사용법을 보여준다. 세션 간 유지. 기본 `70 85`.
- `/context-bar`(인자 없음): 현재 설정(켜짐 여부, 경고 기준)과 마지막 측정값을 한 줄로 보여준다.
- 그 외 인자: 사용법을 보여준다.

## 구조

```
mods/context-bar/
  .claude-plugin/plugin.json   # name·version·description·types
  hooks/hooks.json             # { "modules": ["./register.tsx"] }
  hooks/register.tsx           # 훅 등록, 상태 읽기/쓰기, 렌더
  hooks/bar.ts                 # 순수 함수 (화면·엔진 의존 없음)
  types/index.d.ts             # $.state 계약
  tests/context-bar.test.tsx
```

### `types/index.d.ts`

```ts
export type Segment = { kind: 'used' | 'free' | 'buffer'; color: string; tokens: number }

export type Snapshot = {
  segments: Segment[]
  totalTokens: number
  maxTokens: number   // rawMaxTokens
  percent: number     // breakdown.percentage
}

export type Warn = { yellow: number; red: number }

declare module 'claude-code' {
  interface PluginState {
    'context-bar': {
      enabled: boolean
      warn: Warn
      snapshot: Snapshot | null
    }
  }
}
```

breakdown 전체(grid, memoryFiles 등)는 저장하지 않고 그리는 데 필요한 값만 남긴다.

### `hooks/bar.ts`

- `toSnapshot(breakdown): Snapshot`: `deferred` 행을 빼고 `Segment`로 바꾼다.
- `barWidth(columns): number`: `columns - 24`를 10~30으로 묶는다.
- `layout(segments, width): Cell[]`: 토큰 비율로 칸 수를 나눈다(`Cell = Segment & { cells }`).
  - 토큰이 0보다 큰 구간은 최소 1칸을 받는다.
  - 칸 합계는 항상 `width`다. 반올림 오차는 가장 큰 구간에서 더하거나 뺀다.
- `percentColor(percent, warn): 'error' | 'warning' | undefined`
- `formatTokens(n): string`: `950`, `12k`, `124k`, `1.2M`.
- `parseWarn(args): Warn | string`: 성공하면 `Warn`, 실패하면 사용법 문자열.
- `normalizeWarn(stored): Warn`: store 값이 올바르지 않으면 기본값 `70 85`.

### next-steps 수정

`mods/next-steps/hooks/register.tsx`의 `AbovePrompt` 렌더 훅이 자기 화면(`loading`·`skipped`·제안 목록)을 반환할 때 `await next(e)` 결과를 아래에 함께 그린다.

```tsx
const below = await next(e)
return <Box flexDirection="column">{mine}{below}</Box>
```

- 훅 체인 순서와 상관없이 next-steps는 위, context-bar는 아래에 그려진다.
  - next-steps가 바깥 훅이면: next-steps는 내용을 위에, `next(e)` 결과(context-bar)를 아래에 둔다.
  - context-bar가 바깥 훅이면: context-bar는 `next(e)` 결과(next-steps)를 위에, 자기 막대를 아래에 둔다.

## 테스트

`claude plugin test mods/context-bar`로 실행한다.

- **`bar.ts` 단위**
  - `layout`: 칸 합계 = width, 작은 구간 최소 1칸, 0 토큰 구간 0칸, 구간 하나만 있는 경우
  - `percentColor`: 경계값 69/70/84/85
  - `formatTokens`: 경계 단위
  - `parseWarn`: 정상, 순서 뒤바뀜, 범위 밖, 숫자 아님
  - `toSnapshot`: `deferred` 제외
- **렌더**
  - 측정 전 문구
  - 정상 막대와 비율 문자열
  - 경고 색
  - 꺼짐 시 `next(e)`만 반환
  - `next(e)` 결과가 막대 위에 오는지
- **명령**
  - `on`·`off`의 상태·store 반영
  - `warn` 정상·거부
  - 인자 없음 요약
- **next-steps 회귀**: 기존 테스트 통과, 제안 목록 아래에 `next(e)` 결과가 붙는지

## 문서

`mods/README.md`에 `## context-bar` 절을 추가한다. 무엇을 보여주는지, 명령, `summary` 추정이라 `/context`와 조금 다를 수 있다는 점을 적는다.
