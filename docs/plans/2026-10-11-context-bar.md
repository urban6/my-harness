# context-bar 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬 — superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans로 태스크 단위로 구현한다. 단계는 체크박스(`- [ ]`)로 추적한다.

**Goal:** 입력창 위 밴드에 컨텍스트 창 사용량을 카테고리별 색 막대로 늘 보여주는 `mods/context-bar` 모드를 만든다.

**Architecture:** `session.measure`가 오면 `$.session.usage({ breakdown: 'summary' })`로 카테고리별 토큰을 받아 `$.state`에 줄여 저장하고, `AbovePrompt` 렌더 훅은 그 값만 읽어 그린다. 계산은 순수 함수 모듈(`hooks/bar.ts`)에 모아 단위 테스트로 고정한다. next-steps는 제안을 그릴 때 `next(e)` 결과를 아래에 함께 그리도록 바꿔 두 밴드가 쌓이게 한다.

**Tech Stack:** Claude Code 모드(function hooks 플러그인), TSX(`h` 팩토리), `claude-code` API(`atom`·`read`·`update`·`$.session.usage`·`$.store`), 테스트 키트 `claude-code/testing`, CLI `claude plugin test`·`claude plugin validate`.

**Spec:** `docs/specs/2026-10-11-context-bar-design.md`

## Global Constraints

- 자산 이름은 kebab-case: 모드 이름·명령 이름 모두 `context-bar`.
- 갱신에 API 호출 비용 없음: breakdown은 항상 `'summary'`. `'full'`은 쓰지 않는다.
- 비율은 `breakdown.percentage`(`rawMaxTokens` 대비), 경고 기본값 노랑 70 · 빨강 85, 조건 `0 < 노랑 < 빨강 ≤ 100`(정수).
- 막대 폭 `clamp(bodyColumns - 24, 10, 30)`. `used`=`█`+카테고리 색, `free`=`░`+`inactive`, `buffer`=`·`+`dimColor`, `deferred`는 그리지 않음.
- 설정(`enabled`, `warn`)은 `$.store`로 세션 간 유지. `snapshot`은 `session.start`에서 `null`로 초기화.
- 사용자에게 보이는 문구는 한국어.
- 타입 검사: 설치된 `tsc`는 `.claude-plugin/types`의 선언을 읽지 못한다(next-steps도 같음). 검증은 `claude plugin test`·`claude plugin validate`로만 한다.
- 커밋: 이 저장소(CLAUDE.md)는 사용자가 요청할 때만 커밋한다. 각 태스크의 커밋 단계는 사용자 확인을 받은 뒤 실행한다. 자산 하나 = 커밋 하나이므로 next-steps 수정(태스크 1)과 context-bar(태스크 2~5)는 각각 한 커밋으로 묶는다.

## Review Focus

- `$.store`의 `warn`이 깨져 있음(`{ yellow: 90, red: 50 }`, 문자열 등) → 기본값 70/85로 동작. 테스트: 태스크 4.
- `$.session.usage()`가 예외를 던지거나 `breakdown` 없이 돌아옴 → 이전 막대를 그대로 유지하고 밴드가 깨지지 않음. 테스트: 태스크 3.
- 사용량이 창을 넘음(`percentage` 104, `free` 0) → `104%`를 빨강으로 표시하고 막대 칸 합계는 그대로. 테스트: 태스크 3.
- 좁은 화면(`bodyColumns` 40) → 막대가 16칸으로 줄어 한 줄에 들어감. 테스트: 태스크 3.
- 서브에이전트 압축(`agentId` 있음)이나 `precompute` → 메인 막대는 유지. 테스트: 태스크 3.

---

## 파일 구조

| 파일 | 역할 |
|---|---|
| `mods/next-steps/hooks/register.tsx` (수정) | 제안 밴드 아래에 `next(e)` 결과를 함께 그림 |
| `mods/next-steps/tests/next-steps.test.tsx` (수정) | 함께 그리는지 회귀 테스트 |
| `mods/context-bar/.claude-plugin/plugin.json` | 매니페스트 |
| `mods/context-bar/.claude-plugin/types/` | API 타입 선언. next-steps에서 복사(gitignore 대상) |
| `mods/context-bar/tsconfig.json` | 타입 선언 tsconfig 연결 |
| `mods/context-bar/hooks/hooks.json` | `{ "modules": ["./register.tsx"] }` |
| `mods/context-bar/hooks/bar.ts` | 순수 함수: 스냅샷 변환, 칸 배분, 색, 포맷, 명령 인자 해석 |
| `mods/context-bar/hooks/register.tsx` | 상태, 이벤트 훅, 렌더, 명령 |
| `mods/context-bar/types/index.d.ts` | `$.state` 계약 |
| `mods/context-bar/tests/context-bar.test.tsx` | 단위·렌더·명령 테스트 |
| `mods/README.md` (수정) | `## context-bar` 절 |

---

### Task 1: next-steps가 다른 모드의 밴드를 아래에 함께 그리기

**Files:**
- Modify: `mods/next-steps/hooks/register.tsx:1-2` (import), `mods/next-steps/hooks/register.tsx:221-278` (`AbovePrompt` 렌더 훅)
- Test: `mods/next-steps/tests/next-steps.test.tsx`

**Interfaces:**
- Consumes: 없음
- Produces: next-steps의 `AbovePrompt` 훅은 어떤 상태에서도 `next(e)` 결과를 반환 트리에 포함한다(자기 화면이 있으면 그 아래에). 태스크 3의 context-bar가 이 덕에 체인 순서와 상관없이 맨 아래에 그려진다.

- [ ] **Step 1: 실패하는 테스트 작성**

`mods/next-steps/tests/next-steps.test.tsx`의 `'새 프롬프트가 나가면 늦게 도착한 제안은 버린다'` 테스트 바로 아래에 추가:

```tsx
test('제안이 떠 있어도 다른 모드가 그린 밴드는 아래에 남긴다', async ($, on) => {
  const clock = mock.clock(on)
  engineBand(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => answered('["테스트 추가"]'))

  await $.turn.complete(TURN)
  await clock.settle()

  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ key: 'step-0' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})
```

- [ ] **Step 2: 실패 확인**

Run: `claude plugin test mods/next-steps`
Expected: 새 테스트 1개 FAIL(`engine` Text가 `undefined`), 나머지 19개 PASS.

- [ ] **Step 3: 구현**

`mods/next-steps/hooks/register.tsx` 2행 import에 `RenderElement` 추가:

```tsx
import type { EngineInterface, ModelForkResult, Register, RenderElement } from 'claude-code'
```

`on('ui.render', { component: 'AbovePrompt' }, ...)` 훅 전체를 아래로 교체:

```tsx
  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    // 다른 모드(context-bar 등)가 그린 밴드. 제안이 있으면 그 아래에 쌓는다.
    const below = await next(e)
    if (e.props.hasSurvey || e.props.isWorking) return below

    const now = await read($, phase)
    if (now === 'idle') return below

    const { Box, Button, Text } = $.ui.resolve(e)
    const withBelow = (mine: RenderElement) => (
      <Box flexDirection="column">
        {mine}
        {below}
      </Box>
    )

    if (now === 'loading') return withBelow(<Text dimColor>다음 스텝 정리 중…</Text>)
    if (now === 'skipped') {
      const tokens = formatTokens(await read($, contextTokens))
      return withBelow(
        <Text dimColor>
          대화가 길어(약 {tokens} 토큰) 자동 제안을 건너뛰었어요 · /next-steps로 직접 요청
        </Text>,
      )
    }

    const list = await read($, steps)
    const chosen = await read($, picked)
    const usage = await read($, lastUsage)
    const current = await read($, mode)
    const look = await read($, ui)

    return withBelow(
      <Box flexDirection="column" gap={look.gap}>
        <Text dimColor>다음 스텝 — 숫자로 고르고 0으로 입력창에 채우기</Text>
        {list.map((step, i) => (
          <Button
            key={`step-${i}`}
            plain
            hotkey={String(i + 1)}
            onPress={() => update($, picked, list => toggle(list, i))}
          >
            <Text bold={look.bold}>
              {chosen.includes(i) ? '[x] ' : '[ ] '}
              {step}
            </Text>
          </Button>
        ))}
        <Box flexDirection="row">
          <Button
            key="fill"
            variant="primary"
            hotkey="0"
            label={`입력창에 채우기 (${chosen.length})`}
            onPress={() => fill($)}
          />
          <Text> </Text>
          <Button key="dismiss" role="dismiss" label="닫기" onPress={() => clear($)} />
        </Box>
        {look.tokens && usage !== null && (
          <Text dimColor>
            토큰 {compactUsage(usage)} · {current}
          </Text>
        )}
      </Box>,
    )
  })
```

- [ ] **Step 4: 통과 확인**

Run: `claude plugin test mods/next-steps`
Expected: 20 pass, 0 fail.

Run: `claude plugin validate mods/next-steps`
Expected: `✔ Validation passed` (state read/write 경고는 기존과 같음).

- [ ] **Step 5: 커밋 (사용자 확인 후)**

```bash
git add mods/next-steps/hooks/register.tsx mods/next-steps/tests/next-steps.test.tsx
git commit -m "feat(mods): next-steps 제안 밴드 아래에 다른 모드 밴드를 함께 그림"
```

---

### Task 2: context-bar 뼈대와 순수 함수

**Files:**
- Create: `mods/context-bar/.claude-plugin/plugin.json`, `mods/context-bar/tsconfig.json`, `mods/context-bar/hooks/hooks.json`, `mods/context-bar/hooks/register.tsx`(빈 등록), `mods/context-bar/types/index.d.ts`, `mods/context-bar/hooks/bar.ts`
- Copy: `mods/next-steps/.claude-plugin/types/` → `mods/context-bar/.claude-plugin/types/`
- Test: `mods/context-bar/tests/context-bar.test.tsx`

**Interfaces:**
- Consumes: `claude-code`의 `SessionContextBreakdown`, `ContextCategory`
- Produces (`hooks/bar.ts`):
  - `DEFAULT_WARN: Warn` (`{ yellow: 70, red: 85 }`)
  - `HELP: string`
  - `type Cell = Segment & { cells: number }`
  - `barWidth(columns: number): number`
  - `toSnapshot(b: SessionContextBreakdown): Snapshot`
  - `layout(segments: Segment[], width: number): Cell[]`
  - `percentColor(percent: number, warn: Warn): 'error' | 'warning' | undefined`
  - `formatTokens(n: number): string`
  - `parseWarn(text: string): Warn | string`
  - `normalizeWarn(stored: unknown): Warn`
  - `describe(enabled: boolean, warn: Warn, snap: Snapshot | null): string`
- Produces (`types/index.d.ts`): `Segment`, `Snapshot`, `Warn`, `PluginState['context-bar']`

- [ ] **Step 1: 뼈대 파일 만들기**

```bash
mkdir -p mods/context-bar/.claude-plugin mods/context-bar/hooks mods/context-bar/types mods/context-bar/tests
cp -R mods/next-steps/.claude-plugin/types mods/context-bar/.claude-plugin/types
cp mods/next-steps/tsconfig.json mods/context-bar/tsconfig.json
```

`mods/context-bar/.claude-plugin/plugin.json`:

```json
{
  "name": "context-bar",
  "version": "0.1.0",
  "description": "입력창 위에 컨텍스트 창 사용량을 카테고리별 색 막대로 보여준다",
  "types": "./types/index.d.ts"
}
```

`mods/context-bar/hooks/hooks.json`:

```json
{ "modules": ["./register.tsx"] }
```

`mods/context-bar/hooks/register.tsx` (태스크 3에서 교체):

```tsx
import type { Register } from 'claude-code'

export const register: Register = () => {}
```

`mods/context-bar/types/index.d.ts`:

```ts
// 막대 한 구간. breakdown의 categories에서 deferred(창 밖)를 뺀 것.
export type Segment = {
  kind: 'used' | 'free' | 'buffer'
  // /context가 쓰는 테마 색 키 (promptBorder, permission, inactive, ...)
  color: string
  tokens: number
}

// 마지막 측정값. 그리는 데 필요한 값만 남긴다.
export type Snapshot = {
  segments: Segment[]
  totalTokens: number
  // breakdown.rawMaxTokens
  maxTokens: number
  // breakdown.percentage (rawMaxTokens 대비, 넘치면 100 초과)
  percent: number
}

// 비율 숫자 색 기준(%)
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

- [ ] **Step 2: 실패하는 테스트 작성**

`mods/context-bar/tests/context-bar.test.tsx`:

```tsx
import { expect, test } from 'claude-code/testing'
import type { ContextCategory, SessionContextBreakdown } from 'claude-code'

import {
  barWidth,
  DEFAULT_WARN,
  describe,
  formatTokens,
  HELP,
  layout,
  normalizeWarn,
  parseWarn,
  percentColor,
  toSnapshot,
} from '../hooks/bar'

function category(name: string, kind: ContextCategory['kind'], tokens: number, color = 'inactive'): ContextCategory {
  return { name, kind, tokens, color, isDeferred: kind === 'deferred' }
}

function breakdownOf(categories: ContextCategory[], totalTokens: number, percentage: number): SessionContextBreakdown {
  return {
    categories,
    totalTokens,
    maxTokens: 200_000,
    rawMaxTokens: 200_000,
    autocompactSource: 'auto',
    percentage,
    gridRows: [],
    model: 'opus',
    memoryFiles: [],
    mcpTools: [],
    agents: [],
    autoCompactThreshold: 167_000,
    isAutoCompactEnabled: true,
    apiUsage: null,
  }
}

// 사용 124k / 200k (62%): 시스템 20k, 대화 104k, 남은 공간 43k, 압축 버퍼 33k. MCP 9k는 창 밖.
const NORMAL = breakdownOf(
  [
    category('System prompt', 'used', 20_000, 'promptBorder'),
    category('Messages', 'used', 104_000, 'permission'),
    category('MCP tools', 'deferred', 9_000),
    category('Free space', 'free', 43_000),
    category('Autocompact buffer', 'buffer', 33_000),
  ],
  124_000,
  62,
)

const sumCells = (cells: { cells: number }[]) => cells.reduce((n, c) => n + c.cells, 0)

test('breakdown에서 창 밖(deferred) 구간을 빼고 그릴 값만 남긴다', async () => {
  expect(toSnapshot(NORMAL)).toEqual({
    segments: [
      { kind: 'used', color: 'promptBorder', tokens: 20_000 },
      { kind: 'used', color: 'permission', tokens: 104_000 },
      { kind: 'free', color: 'inactive', tokens: 43_000 },
      { kind: 'buffer', color: 'inactive', tokens: 33_000 },
    ],
    totalTokens: 124_000,
    maxTokens: 200_000,
    percent: 62,
  })
})

test('토큰 비율로 칸을 나누고 합계는 항상 막대 폭이다', async () => {
  const { segments } = toSnapshot(NORMAL)
  expect(layout(segments, 30).map(c => c.cells)).toEqual([3, 16, 6, 5])
  expect(sumCells(layout(segments, 30))).toBe(30)

  // 아주 작은 구간도 1칸은 받고, 차이는 가장 큰 구간이 흡수한다.
  const tiny = layout([
    { kind: 'used', color: 'claude', tokens: 1 },
    { kind: 'free', color: 'inactive', tokens: 999 },
  ], 10)
  expect(tiny.map(c => c.cells)).toEqual([1, 9])

  // 반올림 오차(3+3+3=9)도 채운다.
  const thirds = layout([
    { kind: 'used', color: 'a', tokens: 1 },
    { kind: 'used', color: 'b', tokens: 1 },
    { kind: 'used', color: 'c', tokens: 1 },
  ], 10)
  expect(sumCells(thirds)).toBe(10)

  // 0 토큰 구간은 그리지 않고, 전부 0이면 빈 막대.
  expect(layout([{ kind: 'used', color: 'a', tokens: 0 }, { kind: 'free', color: 'inactive', tokens: 5 }], 10)).toEqual([
    { kind: 'free', color: 'inactive', tokens: 5, cells: 10 },
  ])
  expect(layout([{ kind: 'free', color: 'inactive', tokens: 0 }], 10)).toEqual([])
})

test('막대 폭은 화면 폭에 맞춰 10~30칸', async () => {
  expect(barWidth(80)).toBe(30)
  expect(barWidth(54)).toBe(30)
  expect(barWidth(40)).toBe(16)
  expect(barWidth(20)).toBe(10)
})

test('비율 색은 기준 이상에서 바뀐다', async () => {
  expect(percentColor(69, DEFAULT_WARN)).toBeUndefined()
  expect(percentColor(70, DEFAULT_WARN)).toBe('warning')
  expect(percentColor(84, DEFAULT_WARN)).toBe('warning')
  expect(percentColor(85, DEFAULT_WARN)).toBe('error')
  expect(percentColor(104, DEFAULT_WARN)).toBe('error')
})

test('토큰 수를 짧게 쓴다', async () => {
  expect(formatTokens(950)).toBe('950')
  expect(formatTokens(1_000)).toBe('1k')
  expect(formatTokens(12_345)).toBe('12k')
  expect(formatTokens(124_000)).toBe('124k')
  expect(formatTokens(999_499)).toBe('999k')
  expect(formatTokens(999_500)).toBe('1M')
  expect(formatTokens(1_200_000)).toBe('1.2M')
})

test('경고 기준 인자를 검사한다', async () => {
  expect(parseWarn('60 90')).toEqual({ yellow: 60, red: 90 })
  expect(parseWarn(' 1   100 ')).toEqual({ yellow: 1, red: 100 })
  expect(parseWarn('85 70')).toContain('0 < 노랑 < 빨강 ≤ 100')
  expect(parseWarn('0 50')).toContain('0 < 노랑 < 빨강 ≤ 100')
  expect(parseWarn('70 101')).toContain('0 < 노랑 < 빨강 ≤ 100')
  expect(parseWarn('70 70')).toContain('0 < 노랑 < 빨강 ≤ 100')
  expect(parseWarn('70')).toBe(HELP)
  expect(parseWarn('a b')).toBe(HELP)
  expect(parseWarn('70.5 85')).toBe(HELP)
})

test('저장된 경고 기준이 깨져 있으면 기본값을 쓴다', async () => {
  expect(normalizeWarn({ yellow: 60, red: 90 })).toEqual({ yellow: 60, red: 90 })
  expect(normalizeWarn({ yellow: 90, red: 50 })).toEqual(DEFAULT_WARN)
  expect(normalizeWarn('70 85')).toEqual(DEFAULT_WARN)
  expect(normalizeWarn(undefined)).toEqual(DEFAULT_WARN)
})

test('현재 설정을 한 줄로 요약한다', async () => {
  expect(describe(true, DEFAULT_WARN, null)).toBe('context-bar: 켜짐 · 경고 70% / 85% · 마지막 측정 아직 없음')
  expect(describe(false, { yellow: 60, red: 90 }, toSnapshot(NORMAL))).toBe(
    'context-bar: 꺼짐 · 경고 60% / 90% · 마지막 측정 62% (124k / 200k)',
  )
})
```

- [ ] **Step 3: 실패 확인**

Run: `claude plugin test mods/context-bar`
Expected: FAIL. `../hooks/bar`를 찾지 못함.

- [ ] **Step 4: `hooks/bar.ts` 구현**

```ts
import type { SessionContextBreakdown } from 'claude-code'

import type { Segment, Snapshot, Warn } from '../types'

export const DEFAULT_WARN: Warn = { yellow: 70, red: 85 }
export const HELP = '사용법: /context-bar [on|off|warn <노랑> <빨강>]'
const WARN_RULE = '경고 기준은 0 < 노랑 < 빨강 ≤ 100 인 정수여야 해요. 예: /context-bar warn 70 85'

const MIN_WIDTH = 10
const MAX_WIDTH = 30
// 막대 뒤 "  104% · 999k / 1M" 글자와 여유 칸.
const LABEL_COLUMNS = 24

export type Cell = Segment & { cells: number }

export function barWidth(columns: number): number {
  return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, columns - LABEL_COLUMNS))
}

export function toSnapshot(b: SessionContextBreakdown): Snapshot {
  const segments: Segment[] = b.categories
    .filter(c => c.kind !== 'deferred')
    .map(c => ({ kind: c.kind as Segment['kind'], color: c.color, tokens: c.tokens }))
  return { segments, totalTokens: b.totalTokens, maxTokens: b.rawMaxTokens, percent: b.percentage }
}

function largest(ns: number[]): number {
  let best = 0
  ns.forEach((n, i) => {
    if (n > ns[best]!) best = i
  })
  return best
}

export function layout(segments: Segment[], width: number): Cell[] {
  const shown = segments.filter(s => s.tokens > 0)
  const total = shown.reduce((sum, s) => sum + s.tokens, 0)
  if (total === 0) return []

  const cells = shown.map(s => Math.max(1, Math.round((s.tokens / total) * width)))
  let diff = width - cells.reduce((sum, n) => sum + n, 0)
  // 반올림 오차는 가장 큰 구간이 흡수한다. 1칸짜리는 더 줄이지 않는다.
  while (diff !== 0) {
    const i = largest(cells)
    if (diff < 0 && cells[i]! <= 1) break
    const step = Math.sign(diff)
    cells[i] = cells[i]! + step
    diff -= step
  }
  return shown.map((s, i) => ({ ...s, cells: cells[i]! }))
}

export function percentColor(percent: number, warn: Warn): 'error' | 'warning' | undefined {
  if (percent >= warn.red) return 'error'
  if (percent >= warn.yellow) return 'warning'
  return undefined
}

export function formatTokens(n: number): string {
  if (n < 1_000) return String(n)
  if (n < 999_500) return `${Math.round(n / 1_000)}k`
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`
}

export function parseWarn(text: string): Warn | string {
  const parts = text.trim().split(/\s+/)
  if (parts.length !== 2 || !parts.every(p => /^\d+$/.test(p))) return HELP
  const [yellow, red] = parts.map(Number) as [number, number]
  if (yellow <= 0 || yellow >= red || red > 100) return WARN_RULE
  return { yellow, red }
}

export function normalizeWarn(stored: unknown): Warn {
  if (typeof stored !== 'object' || stored === null) return DEFAULT_WARN
  const { yellow, red } = stored as Partial<Warn>
  const parsed = parseWarn(`${yellow} ${red}`)
  return typeof parsed === 'string' ? DEFAULT_WARN : parsed
}

export function describe(enabled: boolean, warn: Warn, snap: Snapshot | null): string {
  const last = snap === null
    ? '아직 없음'
    : `${snap.percent}% (${formatTokens(snap.totalTokens)} / ${formatTokens(snap.maxTokens)})`
  return `context-bar: ${enabled ? '켜짐' : '꺼짐'} · 경고 ${warn.yellow}% / ${warn.red}% · 마지막 측정 ${last}`
}
```

- [ ] **Step 5: 통과 확인**

Run: `claude plugin test mods/context-bar`
Expected: 8 pass, 0 fail.

(커밋은 태스크 5에서 context-bar 전체를 한 번에 한다.)

---

### Task 3: 측정·압축·렌더 훅

**Files:**
- Modify: `mods/context-bar/hooks/register.tsx` (전체 교체)
- Test: `mods/context-bar/tests/context-bar.test.tsx` (추가)

**Interfaces:**
- Consumes (태스크 2): `barWidth`, `DEFAULT_WARN`, `formatTokens`, `layout`, `normalizeWarn`, `percentColor`, `toSnapshot`, `Cell`; 타입 `Snapshot`, `Warn`
- Produces (태스크 4가 같은 파일에 덧붙임): 모듈 상수 `enabled`, `warn`, `snapshot`(atom), `STORE_ENABLED = 'enabled'`, `STORE_WARN = 'warn'`. 렌더 트리의 키: 막대 줄 `context-bar`, 비율 `percent`, 칸 `cell-<i>`.

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/context-bar.test.tsx` 맨 위 import를 아래로 교체:

```tsx
import { expect, mock, test } from 'claude-code/testing'
import type { ContextCategory, On, SessionContextBreakdown, SessionMeasureInput } from 'claude-code'
```

파일 끝에 추가:

```tsx
const BAND = {
  plugin: 'context-bar',
  component: 'AbovePrompt',
  props: {
    hasSurvey: false,
    isWorking: false,
    maxRows: 20,
    bodyColumns: 80,
    scroll: { offset: 0, bodyRows: 19 },
    view: {},
  },
} as const

const MEASURED: SessionMeasureInput = {
  context: { tokens: 124_000, window: 200_000, percent: 62 },
  rateLimits: [],
  changed: ['context'],
}

// 엔진 쪽 응답. 모드가 next(e)로 넘기면 이 값들이 돌아온다.
function engine(on: On) {
  on('ui.render', { component: 'AbovePrompt' }, ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine</Text>
  })
  on('session.measure', ($, e) => ({ changed: e.changed }))
  on('session.compact', () => ({ messages: [] }))
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('command.register', ($, e) => ({ value: { command: e.name } }))
}

function usageOf(b: SessionContextBreakdown) {
  return { value: { startedAt: 0, context: { tokens: b.totalTokens, window: 200_000, percent: b.percentage, breakdown: b }, rateLimits: [] } }
}

const barText = async (ui: { findAll: (q: { type: string; text: RegExp }) => Promise<{ text: string }[]> }) =>
  (await ui.findAll({ type: 'Text', text: /^[█░·]+$/ })).map(t => t.text).join('')

test('측정 전에는 안내 문구를, 측정 뒤에는 색 막대와 비율을 그린다', async ($, on) => {
  engine(on)
  let asked: unknown
  on('session.usage', ($, e) => {
    asked = e.breakdown
    return usageOf(NORMAL)
  })

  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()

  await $.session.measure(MEASURED)

  expect(asked).toBe('summary')
  const percent = await ui.find({ key: 'percent' })
  expect(percent?.text).toBe('62%')
  expect(percent?.props.color).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: ' · 124k / 200k' })).toBeDefined()
  expect(await barText(ui)).toBe('█'.repeat(19) + '░'.repeat(6) + '·'.repeat(5))
  const used = await ui.findAll({ type: 'Text', text: /^█+$/ })
  expect(used.map(t => t.props.color)).toEqual(['promptBorder', 'permission'])
  // 다른 모드·엔진이 그린 밴드는 막대 위에 남는다.
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('기준을 넘으면 비율 숫자 색이 바뀌고, 창을 넘어도 막대는 깨지지 않는다', async ($, on) => {
  engine(on)
  let current = breakdownOf(NORMAL.categories, 144_000, 72)
  on('session.usage', () => usageOf(current))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })

  await $.session.measure(MEASURED)
  expect((await ui.find({ key: 'percent' }))?.props.color).toBe('warning')

  current = breakdownOf(
    [category('Messages', 'used', 208_000, 'permission'), category('Free space', 'free', 0)],
    208_000,
    104,
  )
  await $.session.measure(MEASURED)
  const percent = await ui.find({ key: 'percent' })
  expect(percent?.text).toBe('104%')
  expect(percent?.props.color).toBe('error')
  expect(await barText(ui)).toBe('█'.repeat(30))
})

test('좁은 화면에서는 막대가 줄어든다', async ($, on) => {
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  await $.session.measure(MEASURED)

  const ui = await $.ui.mount({ ...BAND, props: { ...BAND.props, bodyColumns: 40 }, surface: 'terminal' })
  expect((await barText(ui)).length).toBe(16)
})

test('컨텍스트가 바뀌지 않은 측정에는 다시 계산하지 않는다', async ($, on) => {
  engine(on)
  let calls = 0
  on('session.usage', () => {
    calls += 1
    return usageOf(NORMAL)
  })

  await $.session.measure({ ...MEASURED, changed: ['rateLimits'] })
  expect(calls).toBe(0)
  await $.session.measure(MEASURED)
  expect(calls).toBe(1)
})

test('사용량을 못 읽으면 이전 막대를 그대로 둔다', async ($, on) => {
  engine(on)
  let mode: 'ok' | 'throw' | 'empty' = 'ok'
  on('session.usage', () => {
    if (mode === 'throw') throw new Error('boom')
    if (mode === 'empty') return { value: { startedAt: 0, context: { window: 200_000 }, rateLimits: [] } }
    return usageOf(NORMAL)
  })
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })

  await $.session.measure(MEASURED)
  mode = 'throw'
  await $.session.measure(MEASURED)
  expect((await ui.find({ key: 'percent' }))?.text).toBe('62%')

  mode = 'empty'
  await $.session.measure(MEASURED)
  expect((await ui.find({ key: 'percent' }))?.text).toBe('62%')
})

test('메인 대화가 압축되면 다음 측정까지 측정 전으로 돌아간다', async ($, on) => {
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)

  // 서브에이전트 압축과 미리 계산(precompute)은 메인 막대와 무관하다.
  await $.session.compact({ trigger: 'auto', agentId: 'agent-1', messages: [] })
  await $.session.compact({ trigger: 'precompute', messages: [] })
  expect((await ui.find({ key: 'percent' }))?.text).toBe('62%')

  await $.session.compact({ trigger: 'manual', messages: [] })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
})

test('압축이 건너뛰어지면 막대를 그대로 둔다', async ($, on) => {
  on('ui.render', { component: 'AbovePrompt' }, () => null)
  on('session.measure', ($, e) => ({ changed: e.changed }))
  on('session.compact', () => ({ skip: '막힘' }))
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)

  await $.session.compact({ trigger: 'manual', messages: [] })
  expect((await ui.find({ key: 'percent' }))?.text).toBe('62%')
})

test('설문이 떠 있으면 막대를 그리지 않는다', async ($, on) => {
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  await $.session.measure(MEASURED)

  const ui = await $.ui.mount({ ...BAND, props: { ...BAND.props, hasSurvey: true }, surface: 'terminal' })
  expect(await ui.find({ key: 'percent' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('새 세션이 시작되면 이전 측정값을 버린다', async ($, on) => {
  mock.store(on)
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  await $.session.measure(MEASURED)

  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
})
```

- [ ] **Step 2: 실패 확인**

Run: `claude plugin test mods/context-bar`
Expected: 태스크 2의 8개 PASS, 새로 추가한 9개 FAIL(빈 `register`라 막대가 없음).

- [ ] **Step 3: `hooks/register.tsx` 구현**

```tsx
import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Snapshot, Warn } from '../types'
import type { Cell } from './bar'
import { barWidth, DEFAULT_WARN, formatTokens, layout, normalizeWarn, percentColor, toSnapshot } from './bar'

const enabled = atom({ plugin: 'context-bar', key: 'enabled' } as const, true)
const warn = atom({ plugin: 'context-bar', key: 'warn' } as const, DEFAULT_WARN as Warn)
// 마지막 측정값. 측정 전·압축 직후·꺼짐이면 null.
const snapshot = atom({ plugin: 'context-bar', key: 'snapshot' } as const, null as Snapshot | null)

const STORE_ENABLED = 'enabled'
const STORE_WARN = 'warn'

const GLYPH = { used: '█', free: '░', buffer: '·' } as const

function cellStyle(cell: Cell) {
  if (cell.kind === 'used') return { color: cell.color }
  if (cell.kind === 'free') return { color: 'inactive' }
  return { dimColor: true }
}

// summary는 로컬 추정이라 호출 비용이 없다.
async function measure($: EngineInterface) {
  try {
    const { context } = await $.session.usage({ breakdown: 'summary' })
    const breakdown = context.breakdown
    if (breakdown) await update($, snapshot, () => toSnapshot(breakdown))
  } catch (error) {
    $.ui.log(`context-bar: 컨텍스트 사용량을 읽지 못했어요 (${String(error)})`)
  }
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const storedEnabled = await $.store.get(STORE_ENABLED)
    await update($, enabled, () => storedEnabled !== false)
    const storedWarn = normalizeWarn(await $.store.get(STORE_WARN))
    await update($, warn, () => storedWarn)
    await update($, snapshot, () => null)
    return next(e)
  })

  // 메인 턴이 끝날 때마다 온다. 서브에이전트 턴에는 오지 않는다.
  on('session.measure', async ($, e, next) => {
    const done = await next(e)
    if (e.changed.includes('context') && (await read($, enabled))) await measure($)
    return done
  })

  // 압축된 대화는 다음 응답이 올 때까지 측정값이 없다.
  on('session.compact', async ($, e, next) => {
    const result = await next(e)
    const isApplied = e.agentId === undefined && e.trigger !== 'precompute' && !('skip' in result)
    if (isApplied) await update($, snapshot, () => null)
    return result
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const above = await next(e)
    if (e.props.hasSurvey || !(await read($, enabled))) return above

    const { Box, Text } = $.ui.resolve(e)
    const snap = await read($, snapshot)
    if (snap === null) {
      return (
        <Box flexDirection="column">
          {above}
          <Text key="context-bar" dimColor>컨텍스트 측정 전</Text>
        </Box>
      )
    }

    const cells = layout(snap.segments, barWidth(e.props.bodyColumns))
    const color = percentColor(snap.percent, await read($, warn))
    return (
      <Box flexDirection="column">
        {above}
        <Box key="context-bar" flexDirection="row">
          {cells.map((cell, i) => (
            <Text key={`cell-${i}`} {...cellStyle(cell)}>
              {GLYPH[cell.kind].repeat(cell.cells)}
            </Text>
          ))}
          <Text>  </Text>
          <Text key="percent" {...(color ? { color } : {})}>
            {`${snap.percent}%`}
          </Text>
          <Text dimColor>{` · ${formatTokens(snap.totalTokens)} / ${formatTokens(snap.maxTokens)}`}</Text>
        </Box>
      </Box>
    )
  })
}
```

- [ ] **Step 4: 통과 확인**

Run: `claude plugin test mods/context-bar`
Expected: 17 pass, 0 fail.

주의할 실패:
- `find({ key: 'percent' })`가 비면 `Text`에 `key` prop이 남지 않는 것이다. 그때는 비율 Text를 `ui.find({ type: 'Text', text: /^\d+%$/ })`로 찾도록 테스트를 바꾼다.
- `barText`가 다른 문자열을 돌려주면 `layout` 결과와 칸 수(3·16·6·5)를 먼저 확인한다. 첫 테스트의 `█` 19칸은 `used` 두 구간(3+16)이다.

---

### Task 4: `/context-bar` 명령과 설정 유지

**Files:**
- Modify: `mods/context-bar/hooks/register.tsx` (import, `session.start`, `command.run` 추가)
- Test: `mods/context-bar/tests/context-bar.test.tsx` (추가)

**Interfaces:**
- Consumes (태스크 2): `describe`, `HELP`, `parseWarn`, `normalizeWarn`. 태스크 3의 `enabled`·`warn`·`snapshot`·`STORE_ENABLED`·`STORE_WARN`.
- Produces: 슬래시 명령 `/context-bar [on|off|warn <노랑> <빨강>]`

- [ ] **Step 1: 실패하는 테스트 작성**

파일 끝에 추가:

```tsx
function runCommand(args: string) {
  return {
    command: 'context-bar',
    args,
    origin: { kind: 'composer' as const },
    presentation: { isFullscreen: false, columns: 80 },
  }
}

test('/context-bar off면 막대를 감추고, on이면 다음 측정부터 다시 그린다', async ($, on) => {
  mock.store(on)
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)

  expect((await $.command.run(runCommand('off'))).text).toBe('컨텍스트 막대를 껐어요.')
  expect(await ui.find({ key: 'percent' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()

  // 꺼진 동안의 측정은 무시한다.
  await $.session.measure(MEASURED)
  await $.command.run(runCommand('on'))
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
  await $.session.measure(MEASURED)
  expect((await ui.find({ key: 'percent' }))?.text).toBe('62%')
})

test('/context-bar warn으로 기준을 바꾸면 비율 색에 바로 반영된다', async ($, on) => {
  mock.store(on)
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)
  expect((await ui.find({ key: 'percent' }))?.props.color).toBeUndefined()

  expect((await $.command.run(runCommand('warn 50 60'))).text).toBe('경고 기준: 50% 노랑 · 60% 빨강')
  expect((await ui.find({ key: 'percent' }))?.props.color).toBe('error')
})

test('잘못된 인자는 받지 않고 사용법을 알려 준다', async ($, on) => {
  mock.store(on)
  engine(on)
  expect((await $.command.run(runCommand('warn 85 70'))).text).toContain('0 < 노랑 < 빨강 ≤ 100')
  expect((await $.command.run(runCommand('warn'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('foo'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('on now'))).text).toBe(HELP)
  expect((await $.command.run(runCommand(''))).text).toBe('context-bar: 켜짐 · 경고 70% / 85% · 마지막 측정 아직 없음')
})

test('설정은 세션이 바뀌어도 유지되고, 깨진 값은 기본값으로 읽는다', async ($, on) => {
  mock.store(on, { enabled: false, warn: { yellow: 90, red: 50 } })
  engine(on)
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  expect((await $.command.run(runCommand(''))).text).toBe('context-bar: 꺼짐 · 경고 70% / 85% · 마지막 측정 아직 없음')

  await $.command.run(runCommand('on'))
  await $.command.run(runCommand('warn 60 90'))
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  expect((await $.command.run(runCommand(''))).text).toBe('context-bar: 켜짐 · 경고 60% / 90% · 마지막 측정 아직 없음')
})
```

- [ ] **Step 2: 실패 확인**

Run: `claude plugin test mods/context-bar`
Expected: 기존 17개 PASS, 새로 추가한 4개 FAIL(명령 미등록·미처리).

- [ ] **Step 3: 구현**

`register.tsx`의 bar import에 `describe`, `HELP`, `parseWarn` 추가:

```tsx
import { barWidth, DEFAULT_WARN, describe, formatTokens, HELP, layout, normalizeWarn, parseWarn, percentColor, toSnapshot } from './bar'
```

`session.start` 훅의 `return next(e)` 바로 앞에 명령 등록을 추가:

```tsx
    await $.command.register({
      name: 'context-bar',
      description: '컨텍스트 막대: on/off, warn <노랑> <빨강>, 인자 없으면 현재 설정',
      argumentHint: '[on|off|warn]',
    })
```

`ui.render` 훅 앞에 명령 처리 훅을 추가:

```tsx
  on('command.run', { command: 'context-bar' }, async ($, e) => {
    const [sub = '', ...rest] = e.args.trim().toLowerCase().split(/\s+/)

    if (sub === '') {
      return { text: describe(await read($, enabled), await read($, warn), await read($, snapshot)) }
    }
    if ((sub === 'on' || sub === 'off') && rest.length === 0) {
      const isOn = sub === 'on'
      await update($, enabled, () => isOn)
      await $.store.set(STORE_ENABLED, isOn)
      // 꺼진 동안 측정하지 않으므로, 다시 켰을 때 오래된 값을 보여주지 않는다.
      if (!isOn) await update($, snapshot, () => null)
      return { text: isOn ? '컨텍스트 막대를 켰어요. 다음 턴부터 다시 측정해요.' : '컨텍스트 막대를 껐어요.' }
    }
    if (sub === 'warn') {
      const parsed = parseWarn(rest.join(' '))
      if (typeof parsed === 'string') return { text: parsed }
      await update($, warn, () => parsed)
      await $.store.set(STORE_WARN, parsed)
      return { text: `경고 기준: ${parsed.yellow}% 노랑 · ${parsed.red}% 빨강` }
    }
    return { text: HELP }
  })
```

- [ ] **Step 4: 통과 확인**

Run: `claude plugin test mods/context-bar`
Expected: 21 pass, 0 fail.

---

### Task 5: 문서, 검증, 설치 확인

**Files:**
- Modify: `mods/README.md` (끝에 절 추가)

**Interfaces:**
- Consumes: 태스크 2~4의 명령·동작
- Produces: 없음

- [ ] **Step 1: README 절 추가**

`mods/README.md` 끝에 추가:

~~~markdown

## context-bar

입력창 위 밴드에 컨텍스트 창 사용량을 카테고리별 색 막대로 늘 보여줍니다.

```
███████████████████░░░░░░·····  62% · 124k / 200k
```

- `█` 구간은 카테고리마다 `/context`와 같은 색으로 칠한다. `░`은 남은 공간, `·`는 auto-compact 버퍼
- 비율은 전체 창 대비. 70% 이상 노랑, 85% 이상 빨강
- 메인 턴이 끝날 때마다 갱신한다. 압축 직후와 첫 응답 전에는 `컨텍스트 측정 전`
- 로컬 추정(`summary`)이라 비용이 없지만 `/context`의 값과 조금 다를 수 있다
- `/context-bar`: 현재 설정과 마지막 측정값
- `/context-bar on|off`: 켜기/끄기 (세션 간 유지)
- `/context-bar warn <노랑> <빨강>`: 경고 기준 (예: `warn 70 85`, 세션 간 유지)
- next-steps 제안이 떠 있으면 그 아래, 입력창 바로 위에 그린다
~~~

- [ ] **Step 2: 두 모드 테스트와 검증 실행**

Run: `claude plugin test mods/context-bar && claude plugin test mods/next-steps`
Expected: context-bar 21 pass · next-steps 20 pass, 실패 0.

Run: `claude plugin validate mods/context-bar`
Expected: `✔ Validation passed` (state read/write 목록 경고는 허용).

- [ ] **Step 3: 설치 대상으로 잡히는지 확인 (실제 설치 아님)**

Run: `./install.sh install --global --type mods context-bar --dry-run`
Expected: `mods/context-bar` 경로를 `env.CLAUDE_CODE_PLUGIN_DIRS`에 추가한다는 줄이 나오고 오류가 없다.

- [ ] **Step 4: 커밋 (사용자 확인 후)**

```bash
git add mods/context-bar mods/README.md
git commit -m "feat(mods): 입력창 위 컨텍스트 사용량 막대 context-bar 추가"
```

`mods/context-bar/.claude-plugin/types/`는 그 안의 `.gitignore`(`*`) 때문에 커밋되지 않는다(next-steps와 같음).
