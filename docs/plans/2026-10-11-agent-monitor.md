# agent-monitor 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬 — superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans로 태스크 단위로 구현한다. 단계는 체크박스(`- [ ]`)로 추적한다.

**Goal:** 이 세션에서 띄운 서브에이전트의 상태·경과 시간·도구 호출 수·토큰을 사이드 패널에 실시간으로 보여주는 `mods/agent-monitor` 모드를 만든다.

**Architecture:** `agent.spawn`·`tool.call`·`turn.step`·`turn.complete` 훅이 에이전트별 기록을 `$.state`에 쌓고, 실행 중인 에이전트가 있는 동안만 `$.clock.every(1000)` 틱이 `now`를 갱신하고 `$.agent.list()`로 상태를 보정한다. `Pane` 렌더 훅은 상태만 읽어 그린다. 기록 갱신·정렬·포맷·열 배치는 순수 함수 모듈(`hooks/agents.ts`)에 모아 단위 테스트로 고정한다.

**Tech Stack:** Claude Code 모드(function hooks 플러그인), TSX(`h` 팩토리), `claude-code` API(`atom`·`read`·`update`·`$.agent.list`·`$.clock`·`$.ui.open`·`$.store`), 테스트 키트 `claude-code/testing`(`mock.clock`·`mock.store`·`$.ui.mount`), CLI `claude plugin test`·`claude plugin validate`.

**Spec:** `docs/specs/2026-10-11-agent-monitor-design.md`

## Global Constraints

- 자산 이름은 kebab-case: 모드 이름·명령 이름·패널 id 모두 `agent-monitor`. 패널 제목은 `서브에이전트`.
- 기호와 색: `●` 실행 중(`suggestion`), `✓` 완료(`success`), `✗` 실패(`error`), `■` 중단(`dimColor`).
- 종료 사유 대응: `answer` → `completed`, `aborted` → `killed`, `error`·`refusal` → `failed`.
- 엔진 상태 대응: `pending`·`running`·`waiting`·`idle` → `running`, `completed`·`failed`·`killed`는 그대로.
- 토큰 = `input_tokens + output_tokens + cache_creation_input_tokens + cache_read_input_tokens`의 누적. 비용 환산값이 아니다.
- 보관: 끝난 기록은 `endedAt` 내림차순 `keep`개(기본 10, 1~50 정수), 실행 중 기록은 제한 없음.
- 설정(`keep`, `auto`)만 `$.store`로 세션 간 유지. 기록은 `$.state`(세션 단위), `/clear`·resume에서 비운다.
- 모든 훅은 `next(e)`의 결과를 그대로 돌려준다. 모드 내부 오류는 `$.ui.log('agent-monitor: …')`에만 남긴다.
- `turn.step`은 스트리밍 이벤트다. 플러그인 훅은 `async function* ($, e, next)`로 쓰고 `yield* next(e)`로 결과를 받는다. 테스트 쪽 `turn.step` 응답 훅도 `async function*`이고, 테스트는 `$.turn.step(...)` 스트림을 `next()`로 끝까지 읽어야 훅이 실행된다(사전 확인함).
- `$.clock.now`를 쓰는 테스트는 반드시 `mock.clock(on)`을 먼저 건다.
- 사용자에게 보이는 문구는 한국어.
- 타입 검사: 설치된 `tsc`는 `.claude-plugin/types`의 선언을 읽지 못한다(next-steps·context-bar와 같음). 검증은 `claude plugin test`·`claude plugin validate`로만 한다.
- 커밋: 이 저장소(CLAUDE.md)는 사용자가 요청할 때만 커밋한다. 자산 하나 = 커밋 하나이므로 태스크 1~6을 끝낸 뒤 사용자 확인을 받아 한 커밋으로 묶는다.

## Review Focus

- `$.agent.list()`가 예외를 던짐 → 기록은 그대로, 경과 시간은 계속 흐름. 테스트: 태스크 3.
- 좁은 패널(폭 40)에 긴 한글 작업 설명 → 설명이 `…`로 줄고 한 행이 패널 폭을 넘지 않음. 테스트: 태스크 2.
- 에이전트 실행 중 모드 다시 로드(`session.start`가 다시 옴) → 틱이 다시 돌아 경과 시간이 이어짐. 테스트: 태스크 3.
- `keep`을 현재 끝난 기록 수보다 작게 바꿈 → 즉시 잘리고 실행 중 기록은 남음. 테스트: 태스크 5.
- `$.store`의 `keep`이 깨져 있음(`0`, `"10"`, `99`) → 기본값 10으로 동작. 테스트: 태스크 5.

---

## 파일 구조

| 파일 | 역할 |
|---|---|
| `mods/agent-monitor/.claude-plugin/plugin.json` | 매니페스트 |
| `mods/agent-monitor/.claude-plugin/types/` | API 타입 선언. context-bar에서 복사(gitignore 대상, 에디터용) |
| `mods/agent-monitor/tsconfig.json` | 타입 선언 tsconfig 연결(gitignore 대상) |
| `mods/agent-monitor/hooks/hooks.json` | `{ "modules": ["./register.tsx"] }` |
| `mods/agent-monitor/hooks/agents.ts` | 순수 함수: 기록 갱신·보정·자르기·정렬, 포맷, 열 배치, 명령 인자 해석 |
| `mods/agent-monitor/hooks/register.tsx` | 상태, 이벤트 훅, 틱, 렌더, 명령 |
| `mods/agent-monitor/types/index.d.ts` | `$.state` 계약 |
| `mods/agent-monitor/tests/agent-monitor.test.tsx` | 단위·훅 통합·렌더·명령 테스트 |
| `mods/README.md` (수정) | `## agent-monitor` 절 |
| `README.md` (수정) | 모드 표에 한 줄 |

---

### Task 1: 뼈대와 기록 갱신 순수 함수

**Files:**
- Create: `mods/agent-monitor/.claude-plugin/plugin.json`, `mods/agent-monitor/hooks/hooks.json`, `mods/agent-monitor/types/index.d.ts`, `mods/agent-monitor/hooks/register.tsx`(빈 register), `mods/agent-monitor/hooks/agents.ts`
- Test: `mods/agent-monitor/tests/agent-monitor.test.tsx`

**Interfaces:**
- Consumes: 없음
- Produces (`hooks/agents.ts`):
  - `type SpawnInfo = { subagentType: string; description: string; parentAgentId?: string }`
  - `type Usage = { input_tokens: number; output_tokens: number; cache_read_input_tokens: number; cache_creation_input_tokens: number }`
  - `type ListedAgent = { id: string; status: string }`
  - `type EndReason = 'answer' | 'aborted' | 'error' | 'refusal'`
  - `startRecord(input: SpawnInfo, agentId: string, now: number): AgentRecord`
  - `isTracked(records: AgentRecord[], agentId: string | undefined): boolean`
  - `addToolCall(records: AgentRecord[], agentId: string): AgentRecord[]`
  - `usageTokens(usage: Usage): number`
  - `addTokens(records: AgentRecord[], agentId: string, usage: Usage): AgentRecord[]`
  - `finish(records: AgentRecord[], agentId: string, reason: EndReason, now: number): AgentRecord[]`
  - `reconcile(records: AgentRecord[], listed: ListedAgent[], now: number): AgentRecord[]`
  - `trim(records: AgentRecord[], keep: number): AgentRecord[]`
  - `order(records: AgentRecord[]): AgentRecord[]`
  - `counts(records: AgentRecord[]): { running: number; ended: number }`
- Produces (`types/index.d.ts`): `AgentStatus`, `AgentRecord`, `PluginState['agent-monitor']`

- [ ] **Step 1: 매니페스트·훅 목록·계약·빈 모듈 작성**

`mods/agent-monitor/.claude-plugin/plugin.json`:

```json
{
  "name": "agent-monitor",
  "version": "0.1.0",
  "description": "서브에이전트의 상태·경과 시간·도구 호출 수·토큰을 사이드 패널에 실시간으로 보여준다",
  "types": "./types/index.d.ts"
}
```

`mods/agent-monitor/hooks/hooks.json`:

```json
{ "modules": ["./register.tsx"] }
```

`mods/agent-monitor/types/index.d.ts`:

```ts
export type AgentStatus = 'running' | 'completed' | 'failed' | 'killed'

export type AgentRecord = {
  id: string
  type: string
  description: string
  status: AgentStatus
  startedAt: number
  endedAt?: number
  toolCalls: number
  tokens: number
  parentId?: string
}

declare module 'claude-code' {
  interface PluginState {
    'agent-monitor': {
      records: AgentRecord[]
      now: number
      keep: number
      auto: boolean
      autoOpened: boolean
    }
  }
}
```

`mods/agent-monitor/hooks/register.tsx`(태스크 3에서 채운다):

```tsx
import type { Register } from 'claude-code'

export const register: Register = () => {}
```

에디터용 타입(둘 다 gitignore 대상):

```bash
cp -R mods/context-bar/.claude-plugin/types mods/agent-monitor/.claude-plugin/types
cp mods/context-bar/tsconfig.json mods/agent-monitor/tsconfig.json
```

- [ ] **Step 2: 실패하는 테스트 작성**

`mods/agent-monitor/tests/agent-monitor.test.tsx`:

```tsx
import { expect, test } from 'claude-code/testing'

import type { AgentRecord } from '../types'
import {
  addTokens,
  addToolCall,
  counts,
  finish,
  isTracked,
  order,
  reconcile,
  startRecord,
  trim,
  usageTokens,
} from '../hooks/agents'

const rec = (over: Partial<AgentRecord>): AgentRecord => ({
  id: 'agent-1',
  type: 'Explore',
  description: '탐색',
  status: 'running',
  startedAt: 0,
  toolCalls: 0,
  tokens: 0,
  ...over,
})

const USAGE = { input_tokens: 10, output_tokens: 5, cache_read_input_tokens: 100, cache_creation_input_tokens: 1 }

test('spawn 입력으로 실행 중 기록을 만든다', async () => {
  expect(startRecord({ subagentType: 'backend-impl', description: '주문 서비스 구현' }, 'agent-1', 1_000)).toEqual({
    id: 'agent-1',
    type: 'backend-impl',
    description: '주문 서비스 구현',
    status: 'running',
    startedAt: 1_000,
    toolCalls: 0,
    tokens: 0,
  })
  expect(startRecord({ subagentType: 'Explore', description: 'd', parentAgentId: 'agent-0' }, 'agent-2', 0).parentId).toBe('agent-0')
})

test('추적 중인 에이전트만 호출 수와 토큰을 올린다', async () => {
  const records = [rec({ id: 'agent-1' }), rec({ id: 'agent-2' })]
  expect(isTracked(records, 'agent-2')).toBe(true)
  expect(isTracked(records, 'agent-9')).toBe(false)
  expect(isTracked(records, undefined)).toBe(false)

  const called = addToolCall(addToolCall(records, 'agent-1'), 'agent-1')
  expect(called.map(r => r.toolCalls)).toEqual([2, 0])
  expect(addToolCall(records, 'agent-9')).toEqual(records)

  expect(usageTokens(USAGE)).toBe(116)
  expect(addTokens(called, 'agent-2', USAGE).map(r => r.tokens)).toEqual([0, 116])
  expect(addTokens(records, 'agent-9', USAGE)).toEqual(records)
})

test('종료 사유에 따라 상태를 정하고, 이미 끝난 기록은 그대로 둔다', async () => {
  const running = [rec({})]
  expect(finish(running, 'agent-1', 'answer', 5_000)[0]).toEqual(rec({ status: 'completed', endedAt: 5_000 }))
  expect(finish(running, 'agent-1', 'aborted', 5_000)[0].status).toBe('killed')
  expect(finish(running, 'agent-1', 'error', 5_000)[0].status).toBe('failed')
  expect(finish(running, 'agent-1', 'refusal', 5_000)[0].status).toBe('failed')

  const ended = [rec({ status: 'failed', endedAt: 3_000 })]
  expect(finish(ended, 'agent-1', 'answer', 5_000)).toEqual(ended)
})

test('엔진 목록으로 실행 중 기록을 보정한다', async () => {
  const records = [
    rec({ id: 'a' }),
    rec({ id: 'b' }),
    rec({ id: 'c' }),
    rec({ id: 'd' }),
    rec({ id: 'e', status: 'failed', endedAt: 1_000 }),
  ]
  const listed = [
    { id: 'a', status: 'waiting' },
    { id: 'b', status: 'killed' },
    { id: 'c', status: 'failed' },
    { id: 'e', status: 'completed' },
  ]
  expect(reconcile(records, listed, 9_000)).toEqual([
    rec({ id: 'a' }),
    rec({ id: 'b', status: 'killed', endedAt: 9_000 }),
    rec({ id: 'c', status: 'failed', endedAt: 9_000 }),
    // 목록에서 사라진 실행 중 기록은 엔진이 정리한 것이다.
    rec({ id: 'd', status: 'completed', endedAt: 9_000 }),
    // 이벤트로 이미 정한 상태는 덮어쓰지 않는다.
    rec({ id: 'e', status: 'failed', endedAt: 1_000 }),
  ])
})

test('실행 중은 모두 남기고 끝난 것은 최근 keep개만 남긴다', async () => {
  const records = [
    rec({ id: 'r1' }),
    rec({ id: 'old', status: 'completed', endedAt: 1_000 }),
    rec({ id: 'new', status: 'failed', endedAt: 3_000 }),
    rec({ id: 'mid', status: 'killed', endedAt: 2_000 }),
    rec({ id: 'r2' }),
  ]
  expect(trim(records, 2).map(r => r.id)).toEqual(['r1', 'r2', 'new', 'mid'])
  expect(trim(records, 10)).toHaveLength(5)
})

test('화면 순서: 실행 중은 시작 순, 끝난 것은 최근 순', async () => {
  const records = [
    rec({ id: 'done-old', status: 'completed', startedAt: 0, endedAt: 1_000 }),
    rec({ id: 'run-late', startedAt: 5_000 }),
    rec({ id: 'done-new', status: 'completed', startedAt: 0, endedAt: 4_000 }),
    rec({ id: 'run-early', startedAt: 2_000 }),
  ]
  expect(order(records).map(r => r.id)).toEqual(['run-early', 'run-late', 'done-new', 'done-old'])
  expect(counts(records)).toEqual({ running: 2, ended: 2 })
})
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: FAIL, `../hooks/agents`를 찾지 못함

- [ ] **Step 4: 최소 구현**

`mods/agent-monitor/hooks/agents.ts`:

```ts
import type { AgentRecord, AgentStatus } from '../types'

export type SpawnInfo = { subagentType: string; description: string; parentAgentId?: string }
export type Usage = {
  input_tokens: number
  output_tokens: number
  cache_read_input_tokens: number
  cache_creation_input_tokens: number
}
export type ListedAgent = { id: string; status: string }
export type EndReason = 'answer' | 'aborted' | 'error' | 'refusal'

const REASON_STATUS: Record<EndReason, AgentStatus> = {
  answer: 'completed',
  aborted: 'killed',
  error: 'failed',
  refusal: 'failed',
}

// 엔진 상태 중 끝난 것. pending·running·waiting·idle은 실행 중으로 본다.
const ENDED_STATUS: Record<string, AgentStatus> = { completed: 'completed', failed: 'failed', killed: 'killed' }

export function startRecord(input: SpawnInfo, agentId: string, now: number): AgentRecord {
  return {
    id: agentId,
    type: input.subagentType,
    description: input.description,
    status: 'running',
    startedAt: now,
    toolCalls: 0,
    tokens: 0,
    ...(input.parentAgentId ? { parentId: input.parentAgentId } : {}),
  }
}

export function isTracked(records: AgentRecord[], agentId: string | undefined): boolean {
  return agentId !== undefined && records.some(r => r.id === agentId)
}

function patch(records: AgentRecord[], agentId: string, fn: (r: AgentRecord) => AgentRecord): AgentRecord[] {
  if (!isTracked(records, agentId)) return records
  return records.map(r => (r.id === agentId ? fn(r) : r))
}

export function addToolCall(records: AgentRecord[], agentId: string): AgentRecord[] {
  return patch(records, agentId, r => ({ ...r, toolCalls: r.toolCalls + 1 }))
}

export function usageTokens(usage: Usage): number {
  return usage.input_tokens + usage.output_tokens + usage.cache_read_input_tokens + usage.cache_creation_input_tokens
}

export function addTokens(records: AgentRecord[], agentId: string, usage: Usage): AgentRecord[] {
  return patch(records, agentId, r => ({ ...r, tokens: r.tokens + usageTokens(usage) }))
}

export function finish(records: AgentRecord[], agentId: string, reason: EndReason, now: number): AgentRecord[] {
  return patch(records, agentId, r => (r.status === 'running' ? { ...r, status: REASON_STATUS[reason], endedAt: now } : r))
}

export function reconcile(records: AgentRecord[], listed: ListedAgent[], now: number): AgentRecord[] {
  const byId = new Map(listed.map(a => [a.id, a.status]))
  return records.map(r => {
    if (r.status !== 'running') return r
    const status = byId.get(r.id)
    if (status === undefined) return { ...r, status: 'completed', endedAt: now }
    const ended = ENDED_STATUS[status]
    return ended ? { ...r, status: ended, endedAt: now } : r
  })
}

const isRunning = (r: AgentRecord) => r.status === 'running'
const byStart = (a: AgentRecord, b: AgentRecord) => a.startedAt - b.startedAt
const byEndDesc = (a: AgentRecord, b: AgentRecord) => (b.endedAt ?? 0) - (a.endedAt ?? 0)

export function trim(records: AgentRecord[], keep: number): AgentRecord[] {
  const ended = records.filter(r => !isRunning(r)).sort(byEndDesc).slice(0, keep)
  return [...records.filter(isRunning), ...ended]
}

export function order(records: AgentRecord[]): AgentRecord[] {
  return [...records.filter(isRunning).sort(byStart), ...records.filter(r => !isRunning(r)).sort(byEndDesc)]
}

export function counts(records: AgentRecord[]): { running: number; ended: number } {
  const running = records.filter(isRunning).length
  return { running, ended: records.length - running }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: PASS (6 pass)

---

### Task 2: 포맷·열 배치·명령 인자 순수 함수

**Files:**
- Modify: `mods/agent-monitor/hooks/agents.ts`(아래 함수 추가)
- Test: `mods/agent-monitor/tests/agent-monitor.test.tsx`

**Interfaces:**
- Consumes: 태스크 1의 `AgentRecord`, `counts`
- Produces:
  - 상수 `DEFAULT_KEEP = 10`, `TIME_W = 7`, `CALLS_W = 5`, `TOKENS_W = 5`, `MIN_DESC = 8`, `HELP: string`
  - `formatDuration(ms: number): string`
  - `formatTokens(n: number): string`
  - `displayWidth(text: string): number`
  - `fit(text: string, width: number): string`: 폭에 맞춰 뒤를 공백으로 채우거나 `…`로 자른다
  - `padStart(text: string, width: number): string`: 표시 폭 기준 왼쪽 채움
  - `type Columns = { typeW: number; description: number; showCalls: boolean; showTokens: boolean }`
  - `typeWidth(records: AgentRecord[]): number`: 최대 20
  - `descriptionWidth(records: AgentRecord[]): number`: 최대 30
  - `columnsFor(width: number, typeW: number, descW: number): Columns`
  - `rowText(record: AgentRecord, now: number, cols: Columns): string`
  - `header(records: AgentRecord[]): string`
  - `visible(records: AgentRecord[], maxRows: number): { shown: AgentRecord[]; hidden: number }`
  - `parseKeep(text: string): number | string`
  - `normalizeKeep(stored: unknown): number`

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/agent-monitor.test.tsx`의 import를 다음으로 바꾸고 테스트를 파일 끝에 추가:

```tsx
import {
  addTokens,
  addToolCall,
  columnsFor,
  counts,
  DEFAULT_KEEP,
  descriptionWidth,
  displayWidth,
  finish,
  fit,
  formatDuration,
  formatTokens,
  header,
  HELP,
  isTracked,
  normalizeKeep,
  order,
  parseKeep,
  reconcile,
  rowText,
  startRecord,
  trim,
  typeWidth,
  usageTokens,
  visible,
} from '../hooks/agents'
```

```tsx
test('경과 시간을 짧게 쓴다', async () => {
  expect(formatDuration(0)).toBe('0s')
  expect(formatDuration(48_900)).toBe('48s')
  expect(formatDuration(59_999)).toBe('59s')
  expect(formatDuration(60_000)).toBe('1m 00s')
  expect(formatDuration(134_000)).toBe('2m 14s')
  expect(formatDuration(3_599_000)).toBe('59m 59s')
  expect(formatDuration(3_780_000)).toBe('1h 03m')
  // 시계가 어긋나도 음수로 쓰지 않는다.
  expect(formatDuration(-500)).toBe('0s')
})

test('토큰 수를 짧게 쓴다', async () => {
  expect(formatTokens(950)).toBe('950')
  expect(formatTokens(1_000)).toBe('1k')
  expect(formatTokens(6_200)).toBe('6.2k')
  expect(formatTokens(42_149)).toBe('42.1k')
  expect(formatTokens(124_000)).toBe('124k')
  expect(formatTokens(999_499)).toBe('999k')
  expect(formatTokens(999_500)).toBe('1M')
  expect(formatTokens(1_200_000)).toBe('1.2M')
})

test('한글은 두 칸으로 세고, 폭에 맞춰 채우거나 자른다', async () => {
  expect(displayWidth('Explore')).toBe(7)
  expect(displayWidth('주문 서비스')).toBe(11)
  expect(fit('Explore', 10)).toBe('Explore   ')
  expect(fit('주문 서비스 구현', 8)).toBe('주문 서…')
  expect(displayWidth(fit('주문 서비스 구현', 8))).toBeLessThanOrEqual(8)
  expect(fit('abc', 0)).toBe('')
})

test('좁아지면 설명을 줄이고, 그래도 모자라면 토큰, 호출 수 순으로 뺀다', async () => {
  // 고정 폭: 기호 2 + 타입 17 + 2 + 2 + 시간 7 = 30, 호출 +7, 토큰 +7
  expect(columnsFor(80, 17, 16)).toEqual({ typeW: 17, description: 16, showCalls: true, showTokens: true })
  expect(columnsFor(52, 17, 16)).toEqual({ typeW: 17, description: 8, showCalls: true, showTokens: true })
  expect(columnsFor(50, 17, 16)).toEqual({ typeW: 17, description: 13, showCalls: true, showTokens: false })
  expect(columnsFor(40, 17, 16)).toEqual({ typeW: 17, description: 10, showCalls: false, showTokens: false })
  expect(columnsFor(34, 17, 16)).toEqual({ typeW: 17, description: 4, showCalls: false, showTokens: false })
  // 설명이 짧으면 최소 폭 규칙보다 실제 길이가 먼저다.
  expect(columnsFor(80, 7, 4)).toEqual({ typeW: 7, description: 4, showCalls: true, showTokens: true })
})

test('타입·설명 열 폭은 표시 중인 기록의 가장 긴 값, 각각 최대 20·30칸', async () => {
  const records = [rec({ type: 'Explore', description: '탐색' }), rec({ type: 'boundary-verifier', description: '경계면 검증' })]
  expect(typeWidth(records)).toBe(17)
  expect(descriptionWidth(records)).toBe(11)
  expect(typeWidth([rec({ type: 'a-very-long-agent-type-name' })])).toBe(20)
  expect(descriptionWidth([rec({ description: '아주 긴 작업 설명이 들어오면 삼십 칸에서 자른다' })])).toBe(30)
})

test('한 행: 타입, 설명, 시간, 호출 수, 토큰', async () => {
  const running = rec({ type: 'backend-impl', description: '주문 서비스 구현', startedAt: 1_000, toolCalls: 18, tokens: 42_149 })
  const cols = { typeW: 12, description: 16, showCalls: true, showTokens: true }
  expect(rowText(running, 135_000, cols)).toBe('backend-impl  주문 서비스 구현   2m 14s   18회  42.1k')

  const done = rec({ ...running, status: 'completed', endedAt: 49_000 })
  expect(rowText(done, 999_000, cols)).toBe('backend-impl  주문 서비스 구현      48s   18회  42.1k')

  const narrow = { typeW: 12, description: 6, showCalls: false, showTokens: false }
  expect(rowText(running, 135_000, narrow)).toBe('backend-impl  주문 …   2m 14s')
})

test('긴 한글 설명이 좁은 패널에서도 한 행 폭을 넘지 않는다', async () => {
  const records = [rec({ type: 'general-purpose', description: '결제 모듈의 환불 흐름과 예외 처리를 전부 분석' })]
  const cols = columnsFor(40, typeWidth(records), descriptionWidth(records))
  // 행 앞의 기호와 공백 2칸을 더해도 40칸 안이다.
  expect(2 + displayWidth(rowText(records[0], 60_000, cols))).toBeLessThanOrEqual(40)
  expect(rowText(records[0], 60_000, cols)).toContain('…')
})

test('머리줄은 0인 항목을 뺀다', async () => {
  expect(header([])).toBe('서브에이전트')
  expect(header([rec({}), rec({ id: 'b' })])).toBe('서브에이전트 · 실행 중 2')
  expect(header([rec({}), rec({ id: 'b', status: 'killed', endedAt: 1 })])).toBe('서브에이전트 · 실행 중 1 · 완료 1')
  expect(header([rec({ status: 'failed', endedAt: 1 })])).toBe('서브에이전트 · 완료 1')
})

test('높이를 넘으면 앞에서부터 채우고 나머지 수를 센다', async () => {
  const five = ['a', 'b', 'c', 'd', 'e'].map(id => rec({ id }))
  expect(visible(five, 10)).toEqual({ shown: five, hidden: 0 })
  expect(visible(five, 5)).toEqual({ shown: five, hidden: 0 })
  // 마지막 줄은 "… 외 N개" 자리다.
  expect(visible(five, 3)).toEqual({ shown: five.slice(0, 2), hidden: 3 })
  expect(visible(five, 0)).toEqual({ shown: [], hidden: 5 })
})

test('보관 개수 인자를 검사하고, 저장된 값이 깨져 있으면 기본값을 쓴다', async () => {
  expect(parseKeep('5')).toBe(5)
  expect(parseKeep(' 50 ')).toBe(50)
  expect(parseKeep('0')).toBe('보관 개수는 1~50 사이 정수예요.')
  expect(parseKeep('51')).toBe('보관 개수는 1~50 사이 정수예요.')
  expect(parseKeep('3.5')).toBe(HELP)
  expect(parseKeep('many')).toBe(HELP)
  expect(parseKeep('')).toBe(HELP)

  expect(normalizeKeep(20)).toBe(20)
  expect(normalizeKeep(0)).toBe(DEFAULT_KEEP)
  expect(normalizeKeep(99)).toBe(DEFAULT_KEEP)
  expect(normalizeKeep('10')).toBe(DEFAULT_KEEP)
  expect(normalizeKeep(undefined)).toBe(DEFAULT_KEEP)
})
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: FAIL, `formatDuration` 등이 export되지 않음

- [ ] **Step 3: 최소 구현**

`hooks/agents.ts` 끝에 추가:

```ts
export const DEFAULT_KEEP = 10
export const TIME_W = 7
export const CALLS_W = 5
export const TOKENS_W = 5
export const MIN_DESC = 8
const MAX_TYPE_W = 20
const MAX_DESC_W = 30
const GAP = '  '

export const HELP = [
  '사용법:',
  '/agent-monitor — 패널 열기',
  '/agent-monitor keep <1~50> — 끝난 에이전트 보관 개수',
  '/agent-monitor clear — 끝난 기록 지우기',
  '/agent-monitor auto on|off — 첫 에이전트가 뜰 때 패널 자동 열기',
].join('\n')

const pad2 = (n: number) => String(n).padStart(2, '0')

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1_000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${pad2(s % 60)}s`
  return `${Math.floor(m / 60)}h ${pad2(m % 60)}m`
}

export function formatTokens(n: number): string {
  if (n < 1_000) return String(n)
  if (n < 100_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, '')}k`
  if (n < 999_500) return `${Math.round(n / 1_000)}k`
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`
}

const isWide = (ch: string) => /[ᄀ-ᇿ㄰-㆏가-힣]/.test(ch)

export function displayWidth(text: string): number {
  return [...text].reduce((n, ch) => n + (isWide(ch) ? 2 : 1), 0)
}

export function fit(text: string, width: number): string {
  if (width <= 0) return ''
  const w = displayWidth(text)
  if (w <= width) return text + ' '.repeat(width - w)
  let out = ''
  let used = 0
  for (const ch of text) {
    const cw = isWide(ch) ? 2 : 1
    if (used + cw > width - 1) break
    out += ch
    used += cw
  }
  return out + '…' + ' '.repeat(width - 1 - used)
}

export function padStart(text: string, width: number): string {
  return ' '.repeat(Math.max(0, width - displayWidth(text))) + text
}

export type Columns = { typeW: number; description: number; showCalls: boolean; showTokens: boolean }

export function typeWidth(records: AgentRecord[]): number {
  return Math.min(MAX_TYPE_W, Math.max(0, ...records.map(r => displayWidth(r.type))))
}

export function descriptionWidth(records: AgentRecord[]): number {
  return Math.min(MAX_DESC_W, Math.max(0, ...records.map(r => displayWidth(r.description))))
}

// 행: 기호 + 공백(2) · 타입 · 설명 · 시간 [· 호출 수] [· 토큰], 열 사이는 공백 두 칸.
export function columnsFor(width: number, typeW: number, descW: number): Columns {
  const base = 2 + typeW + GAP.length + GAP.length + TIME_W
  const options: [boolean, boolean][] = [
    [true, true],
    [true, false],
    [false, false],
  ]
  for (const [showCalls, showTokens] of options) {
    const room =
      width - base - (showCalls ? GAP.length + CALLS_W : 0) - (showTokens ? GAP.length + TOKENS_W : 0)
    if (room >= Math.min(MIN_DESC, descW)) return { typeW, description: Math.min(descW, room), showCalls, showTokens }
  }
  return { typeW, description: Math.max(0, Math.min(descW, width - base)), showCalls: false, showTokens: false }
}

export function rowText(record: AgentRecord, now: number, cols: Columns): string {
  const elapsed = (record.endedAt ?? now) - record.startedAt
  const parts = [fit(record.type, cols.typeW), fit(record.description, cols.description), padStart(formatDuration(elapsed), TIME_W)]
  if (cols.showCalls) parts.push(padStart(`${record.toolCalls}회`, CALLS_W))
  if (cols.showTokens) parts.push(padStart(formatTokens(record.tokens), TOKENS_W))
  return parts.join(GAP)
}

export function header(records: AgentRecord[]): string {
  const { running, ended } = counts(records)
  const parts = ['서브에이전트']
  if (running > 0) parts.push(`실행 중 ${running}`)
  if (ended > 0) parts.push(`완료 ${ended}`)
  return parts.join(' · ')
}

export function visible(records: AgentRecord[], maxRows: number): { shown: AgentRecord[]; hidden: number } {
  if (records.length <= maxRows) return { shown: records, hidden: 0 }
  const shown = records.slice(0, Math.max(0, maxRows - 1))
  return { shown, hidden: records.length - shown.length }
}

export function parseKeep(text: string): number | string {
  const value = text.trim()
  if (!/^\d+$/.test(value)) return HELP
  const n = Number(value)
  if (n < 1 || n > 50) return '보관 개수는 1~50 사이 정수예요.'
  return n
}

export function normalizeKeep(stored: unknown): number {
  return typeof stored === 'number' && Number.isInteger(stored) && stored >= 1 && stored <= 50 ? stored : DEFAULT_KEEP
}
```

참고: `rowText` 기대값의 공백은 `fit`·`padStart`·`GAP`에서 나온다. `'주문 서비스 구현'`은 표시 폭 16이라 채움 없이 들어가고, 시간 `2m 14s`(6)는 7칸에 맞춰 앞에 공백 1칸, 호출 `18회`(4)는 5칸에 맞춰 공백 1칸, 토큰 `42.1k`(5)는 그대로다.

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: PASS (16 pass)

---

### Task 3: 이벤트 훅과 1초 틱

**Files:**
- Modify: `mods/agent-monitor/hooks/register.tsx`(전체 교체)
- Test: `mods/agent-monitor/tests/agent-monitor.test.tsx`

**Interfaces:**
- Consumes: 태스크 1·2의 `startRecord`, `isTracked`, `addToolCall`, `addTokens`, `finish`, `reconcile`, `trim`, `DEFAULT_KEEP`, `normalizeKeep`
- Produces:
  - 상태 atom `records`, `now`, `keep`, `auto`, `autoOpened`(`plugin: 'agent-monitor'`)
  - 상수 `PANE = 'agent-monitor'`, `TITLE = '서브에이전트'`
  - 훅: `session.start`, `session.end`, `agent.spawn`, `tool.call`, `turn.step`, `turn.complete`
  - 태스크 4·5가 같은 파일의 `register` 안에 `ui.render`·`command.run` 훅을 더한다

- [ ] **Step 1: 실패하는 테스트 작성**

import 맨 위를 다음으로 바꾼다:

```tsx
import { expect, mock, test } from 'claude-code/testing'
import type { AgentInfo, AgentSpawnInput, EngineInterface, On } from 'claude-code'
```

`agents` import 아래에 픽스처와 테스트 추가:

```tsx
const SPAWN: AgentSpawnInput = {
  tool_use_id: 'tu-1',
  prompt: '주문 서비스를 구현해 줘',
  description: '주문 서비스 구현',
  subagentType: 'backend-impl',
  provider: { plugin: 'engine', tier: 'core' },
  parentModel: 'opus',
  background: false,
  fork: false,
}

type World = {
  spawned: string[]
  // 지정하지 않으면 띄운 에이전트가 모두 실행 중인 목록을 돌려준다.
  listed?: AgentInfo[]
  usage: Record<string, typeof USAGE>
  opened: string[]
  listFails: boolean
  denySpawn: boolean
}

// 엔진 쪽 응답. 모드가 next(e)로 넘기면 이 값들이 돌아온다.
function engine(on: On): World {
  const world: World = { spawned: [], usage: {}, opened: [], listFails: false, denySpawn: false }
  on('agent.spawn', () => {
    if (world.denySpawn) return { deny: '막힘' }
    const agentId = `agent-${world.spawned.length + 1}`
    world.spawned.push(agentId)
    return { model: 'haiku', agentId }
  })
  on('agent.list', () => {
    if (world.listFails) throw new Error('boom')
    return { value: world.listed ?? world.spawned.map(id => running(id)) }
  })
  on('ui.open', ($, e) => {
    world.opened.push(e.id)
    return { value: { isPlaced: true } }
  })
  on('ui.log', () => ({ value: undefined }))
  on('tool.call', () => ({ result: { stdout: '', stderr: '' } }))
  on('turn.step', async function* ($, e) {
    const usage = e.agentId ? world.usage[e.agentId] : undefined
    return {
      turnId: e.turnId,
      index: e.index,
      answer: '',
      toolUses: [],
      stopReason: 'end_turn',
      usage: usage ? { ...usage, model: 'haiku' } : null,
    }
  })
  on('turn.complete', ($, e) => ({ text: e.answer }))
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('session.end', ($, e) => ({ sessionId: e.sessionId }))
  on('command.register', ($, e) => ({ value: { command: e.name } }))
  return world
}

const running = (id: string, type = 'backend-impl'): AgentInfo => ({ id, type, description: '', status: 'running' })

async function step($: EngineInterface, agentId?: string) {
  const stream = $.turn.step({ turnId: `turn-${agentId ?? 'main'}`, index: 0, model: 'haiku', messageCount: 1, agentId })
  // 스트림을 끝까지 읽어야 훅 체인이 결과를 받는다.
  while (!(await stream.next()).done) {}
}

function complete($: EngineInterface, agentId: string | undefined, reason: 'answer' | 'aborted' | 'error') {
  return $.turn.complete({ answer: '', durationMs: 1, isAborted: reason === 'aborted', turnId: 't', reason, agentId })
}

const PANE_TARGET = {
  plugin: 'agent-monitor',
  component: 'Pane',
  requestId: 'agent-monitor',
  viewport: { columns: 82, rows: 24 },
  props: {
    title: '서브에이전트',
    isFocused: false,
    bodyColumns: 80,
    placement: 'dock',
    scroll: { offset: 0, bodyRows: 20 },
    view: {},
  },
} as const

const rowOf = async (ui: { find: (q: { type: string; text: RegExp }) => Promise<{ text: string } | undefined> }, type: string) =>
  (await ui.find({ type: 'Text', text: new RegExp(`^ ${type} `) }))?.text

test('spawn → 도구 호출 → 토큰 → 종료가 한 기록에 쌓인다', async ($, on) => {
  const clock = mock.clock(on, { now: 1_000 })
  const world = engine(on)
  world.usage['agent-1'] = USAGE

  await $.agent.spawn(SPAWN)
  await $.tool.call({ tool: 'Bash', command: 'ls', agentId: 'agent-1' })
  await $.tool.call({ tool: 'Read', file_path: 'a.ts', agentId: 'agent-1' })
  await step($, 'agent-1')
  await step($, 'agent-1')
  await clock.advance(48_000)
  await complete($, 'agent-1', 'answer')

  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await rowOf(ui, 'backend-impl')).toBe(' backend-impl  주문 서비스 구현      48s    2회    232')
  expect(await ui.find({ type: 'Text', text: '✓' })).toBeDefined()
})

test('메인 루프의 도구 호출·토큰은 세지 않는다', async ($, on) => {
  mock.clock(on)
  const world = engine(on)
  world.usage['agent-1'] = USAGE

  await $.agent.spawn(SPAWN)
  await $.tool.call({ tool: 'Bash', command: 'ls' })
  await step($, undefined)
  // 모르는 루프(워크플로 등)도 무시한다.
  await $.tool.call({ tool: 'Bash', command: 'ls', agentId: 'agent-9' })

  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await rowOf(ui, 'backend-impl')).toContain('  0회      0')
})

test('거부된 spawn은 기록하지 않는다', async ($, on) => {
  mock.clock(on)
  const world = engine(on)
  world.denySpawn = true

  await $.agent.spawn(SPAWN)
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '아직 실행된 서브에이전트가 없어요' })).toBeDefined()
})

test('중단·오류로 끝나면 상태가 다르게 남는다', async ($, on) => {
  mock.clock(on)
  engine(on)
  await $.agent.spawn(SPAWN)
  await $.agent.spawn({ ...SPAWN, tool_use_id: 'tu-2', subagentType: 'Explore', description: '탐색' })

  await complete($, 'agent-1', 'aborted')
  await complete($, 'agent-2', 'error')

  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '■' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '✗' })).toBeDefined()
})

test('실행 중이면 1초마다 경과 시간이 오르고, 엔진 목록으로 끝난 상태를 맞춘다', async ($, on) => {
  const clock = mock.clock(on)
  const world = engine(on)
  await $.agent.spawn(SPAWN)
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })

  await clock.advance(3_000)
  expect(await rowOf(ui, 'backend-impl')).toContain('3s')

  // 이벤트 없이 엔진이 끝냈다(kill 등).
  world.listed = [{ ...running('agent-1'), status: 'killed' }]
  await clock.advance(1_000)
  expect(await ui.find({ type: 'Text', text: '■' })).toBeDefined()

  // 끝난 뒤에는 시간이 멈춘다.
  await clock.advance(10_000)
  expect(await rowOf(ui, 'backend-impl')).toContain('4s')
})

test('엔진 목록을 못 읽어도 기록은 그대로, 시간은 계속 흐른다', async ($, on) => {
  const clock = mock.clock(on)
  const world = engine(on)
  await $.agent.spawn(SPAWN)
  world.listFails = true
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })

  await clock.advance(5_000)
  expect(await rowOf(ui, 'backend-impl')).toContain('5s')
  expect(await ui.find({ type: 'Text', text: '●' })).toBeDefined()
})

test('첫 에이전트에서 패널을 한 번만 자동으로 연다', async ($, on) => {
  mock.clock(on)
  mock.store(on)
  const world = engine(on)
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })

  await $.agent.spawn(SPAWN)
  await $.agent.spawn({ ...SPAWN, tool_use_id: 'tu-2' })
  expect(world.opened).toEqual(['agent-monitor'])
})

test('실행 중에 session.start가 다시 와도 틱이 이어진다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  engine(on)
  await $.agent.spawn(SPAWN)

  // 다시 로드되면 session.start가 다시 온다. 키트는 모듈을 새로 띄우지 않으므로
  // 여기서는 틱이 중복되거나 멈추지 않는지만 본다. 실제 다시 로드는 태스크 6에서 확인한다.
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  await clock.advance(2_000)
  expect(await rowOf(ui, 'backend-impl')).toContain('2s')
})

test('/clear·resume로 대화가 바뀌면 기록을 비운다', async ($, on) => {
  mock.clock(on)
  engine(on)
  await $.agent.spawn(SPAWN)

  await $.session.end({ reason: 'clear', sessionId: 's-1', resume: { id: 's-1' } })
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '아직 실행된 서브에이전트가 없어요' })).toBeDefined()
})
```

이 테스트들은 패널을 그려서 확인하므로, Step 3에서 `Pane` 렌더 훅도 함께 넣는다. 태스크 4는 화면 규칙만 테스트로 더 고정한다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: FAIL, 새 테스트 9개 실패(기록이 생기지 않고 패널을 그리는 훅이 없음)

- [ ] **Step 3: 구현**

`mods/agent-monitor/hooks/register.tsx` 전체:

```tsx
import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register, Timer } from 'claude-code'

import type { AgentRecord, AgentStatus } from '../types'
import {
  addTokens,
  addToolCall,
  columnsFor,
  DEFAULT_KEEP,
  descriptionWidth,
  finish,
  header,
  isTracked,
  normalizeKeep,
  order,
  reconcile,
  rowText,
  startRecord,
  trim,
  typeWidth,
  visible,
} from './agents'

const PANE = 'agent-monitor'
const TITLE = '서브에이전트'

const records = atom({ plugin: 'agent-monitor', key: 'records' } as const, [] as AgentRecord[])
// 틱이 갱신하는 현재 시각. 렌더는 이 값으로 경과 시간을 계산한다.
const now = atom({ plugin: 'agent-monitor', key: 'now' } as const, 0)
const keep = atom({ plugin: 'agent-monitor', key: 'keep' } as const, DEFAULT_KEEP)
const auto = atom({ plugin: 'agent-monitor', key: 'auto' } as const, true)
const autoOpened = atom({ plugin: 'agent-monitor', key: 'autoOpened' } as const, false)

const STORE_KEEP = 'keep'
const STORE_AUTO = 'auto'

const SYMBOL: Record<AgentStatus, string> = { running: '●', completed: '✓', failed: '✗', killed: '■' }

function symbolStyle(status: AgentStatus) {
  if (status === 'running') return { color: 'suggestion' }
  if (status === 'completed') return { color: 'success' }
  if (status === 'failed') return { color: 'error' }
  return { dimColor: true }
}

// 모듈 변수라 다시 로드되면 사라진다. session.start에서 필요하면 다시 시작한다.
let tick: Timer | undefined

async function guard($: EngineInterface, work: () => Promise<void>) {
  try {
    await work()
  } catch (error) {
    $.ui.log(`agent-monitor: ${String(error)}`)
  }
}

const hasRunning = (list: AgentRecord[]) => list.some(r => r.status === 'running')

function stopTick() {
  tick?.cancel()
  tick = undefined
}

// 엔진 목록으로 놓친 종료를 맞추고 현재 시각을 올린다. 목록을 못 읽어도 시간은 흐른다.
async function refresh($: EngineInterface) {
  const at = await $.clock.now()
  const listed = await $.agent.list().catch(() => undefined)
  if (listed) {
    const limit = await read($, keep)
    await update($, records, list => trim(reconcile(list, listed, at), limit))
  }
  await update($, now, () => at)
  if (!hasRunning(await read($, records))) stopTick()
}

function startTick($: EngineInterface) {
  if (tick) return
  tick = $.clock.every(1_000, () => {
    void guard($, () => refresh($))
  })
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const storedKeep = normalizeKeep(await $.store.get(STORE_KEEP))
    await update($, keep, () => storedKeep)
    const storedAuto = await $.store.get(STORE_AUTO)
    await update($, auto, () => storedAuto !== false)
    await $.command.register({
      name: 'agent-monitor',
      description: '서브에이전트 패널: 인자 없으면 열기, keep <N>, clear, auto on|off',
      argumentHint: '[keep|clear|auto]',
    })
    if (hasRunning(await read($, records))) startTick($)
    return next(e)
  })

  // /clear·resume에는 session.start가 오지 않는다. 대화가 바뀌었으니 기록을 버린다.
  on('session.end', async ($, e, next) => {
    if (e.reason === 'clear' || e.reason === 'resume') {
      stopTick()
      await update($, records, () => [])
      await update($, autoOpened, () => false)
    }
    return next(e)
  })

  on('agent.spawn', async ($, e, next) => {
    const result = await next(e)
    if (result.deny !== undefined || result.agentId === undefined) return result
    const agentId = result.agentId
    await guard($, async () => {
      const at = await $.clock.now()
      await update($, records, list => [...list, startRecord(e, agentId, at)])
      await update($, now, () => at)
      startTick($)
      if ((await read($, auto)) && !(await read($, autoOpened))) {
        await update($, autoOpened, () => true)
        await $.ui.open({ id: PANE, title: TITLE })
      }
    })
    return result
  })

  on('tool.call', async ($, e, next) => {
    const agentId = e.agentId
    if (agentId !== undefined && isTracked(await read($, records), agentId)) {
      await guard($, () => update($, records, list => addToolCall(list, agentId)))
    }
    return next(e)
  })

  on('turn.step', async function* ($, e, next) {
    const result = yield* next(e)
    const agentId = e.agentId
    const usage = result.usage
    if (agentId !== undefined && usage && isTracked(await read($, records), agentId)) {
      await guard($, () => update($, records, list => addTokens(list, agentId, usage)))
    }
    return result
  })

  on('turn.complete', async ($, e, next) => {
    const result = await next(e)
    const agentId = e.agentId
    if (agentId !== undefined && isTracked(await read($, records), agentId)) {
      await guard($, async () => {
        const at = await $.clock.now()
        const limit = await read($, keep)
        await update($, records, list => trim(finish(list, agentId, e.reason, at), limit))
        await update($, now, () => at)
      })
    }
    return result
  })

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Text } = $.ui.resolve(e)
    const list = order(await read($, records))
    if (list.length === 0) return <Text dimColor>아직 실행된 서브에이전트가 없어요</Text>

    const at = await read($, now)
    const cols = columnsFor(e.props.bodyColumns, typeWidth(list), descriptionWidth(list))
    // 머리줄과 빈 줄 두 행을 뺀 만큼 그린다.
    const { shown, hidden } = visible(list, e.props.scroll.bodyRows - 2)
    return (
      <Box flexDirection="column">
        <Text key="header" bold>{header(list)}</Text>
        <Text> </Text>
        {shown.map(r => (
          <Box key={`agent-${r.id}`} flexDirection="row">
            <Text {...symbolStyle(r.status)}>{SYMBOL[r.status]}</Text>
            <Text dimColor={r.status === 'killed'}>{` ${rowText(r, at, cols)}`}</Text>
          </Box>
        ))}
        {hidden > 0 && <Text dimColor>{`… 외 ${hidden}개`}</Text>}
      </Box>
    )
  })
}
```

참고:
- `e.reason`의 타입에는 `refusal`이 포함되며 `EndReason`과 같다.
- `$.clock.every`의 콜백은 `() => void`라 `refresh`를 `void`로 띄우고 `guard`로 감싼다.

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: PASS (25 pass)

첫 통합 테스트의 기대 행 `' backend-impl  주문 서비스 구현      48s    2회    232'`은 타입 폭 12, 설명 폭 16, 패널 폭 80에서 `columnsFor`가 설명 16·호출·토큰을 모두 보이게 하므로 나온다. 토큰 232 = (10+5+100+1)×2. 실패하면 행 문자열을 출력해 공백 수만 비교한다(계산 규칙은 태스크 2에서 고정됨).

- [ ] **Step 5: 정적 검사**

Run: `claude plugin validate mods/agent-monitor`
Expected: `Validation passed` (author 경고만). hooks 목록에 `session.start, session.end, agent.spawn, tool.call, turn.step, turn.complete, ui.render{component=Pane, requestId=agent-monitor}`가 보인다.

---

### Task 4: 패널 렌더 세부

**Files:**
- Modify: 없음(렌더 훅은 태스크 3에서 들어감). 테스트만 추가해 화면 규칙을 고정한다.
- Test: `mods/agent-monitor/tests/agent-monitor.test.tsx`

**Interfaces:**
- Consumes: 태스크 3의 렌더 훅, `PANE_TARGET`, `engine`, `SPAWN`, `running`
- Produces: 없음

- [ ] **Step 1: 테스트 작성**

```tsx
for (const surface of ['terminal', 'desktop'] as const) {
  test(`${surface}: 머리줄과 순서 — 실행 중이 위, 끝난 것이 아래`, async ($, on) => {
    const clock = mock.clock(on)
    engine(on)
    await $.agent.spawn({ ...SPAWN, subagentType: 'Explore', description: '탐색' })
    await clock.advance(1_000)
    await $.agent.spawn(SPAWN)
    await complete($, 'agent-1', 'answer')

    const ui = await $.ui.mount({ ...PANE_TARGET, surface })
    expect(await ui.find({ type: 'Text', text: '서브에이전트 · 실행 중 1 · 완료 1' })).toBeDefined()
    const rows = (await ui.findAll({ type: 'Text', text: /^ (Explore|backend-impl) / })).map(t => t.text.trim().split(' ')[0])
    expect(rows).toEqual(['backend-impl', 'Explore'])
  })
}

test('행 색: 실행 중 suggestion, 완료 success, 실패 error, 중단은 흐리게', async ($, on) => {
  mock.clock(on)
  engine(on)
  for (const n of [1, 2, 3, 4]) await $.agent.spawn({ ...SPAWN, tool_use_id: `tu-${n}` })
  await complete($, 'agent-2', 'answer')
  await complete($, 'agent-3', 'error')
  await complete($, 'agent-4', 'aborted')

  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect((await ui.find({ type: 'Text', text: '●' }))?.props.color).toBe('suggestion')
  expect((await ui.find({ type: 'Text', text: '✓' }))?.props.color).toBe('success')
  expect((await ui.find({ type: 'Text', text: '✗' }))?.props.color).toBe('error')
  expect((await ui.find({ type: 'Text', text: '■' }))?.props.dimColor).toBe(true)
})

test('패널 높이를 넘으면 끝난 것을 자르고 남은 수를 쓴다', async ($, on) => {
  mock.clock(on)
  engine(on)
  for (const n of [1, 2, 3, 4, 5, 6]) await $.agent.spawn({ ...SPAWN, tool_use_id: `tu-${n}` })
  for (const n of [4, 5, 6]) await complete($, `agent-${n}`, 'answer')

  // bodyRows 6 - 머리줄 2 = 4행: 실행 중 3 + "… 외 3개"
  const small = { ...PANE_TARGET.props, scroll: { offset: 0, bodyRows: 6 } }
  const ui = await $.ui.mount({ ...PANE_TARGET, props: small, surface: 'terminal' })
  expect(await ui.findAll({ type: 'Text', text: '●' })).toHaveLength(3)
  expect(await ui.find({ type: 'Text', text: '✓' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: '… 외 3개' })).toBeDefined()
})

test('좁은 패널에서는 토큰 열부터 뺀다', async ($, on) => {
  mock.clock(on)
  const world = engine(on)
  world.usage['agent-1'] = USAGE
  await $.agent.spawn(SPAWN)
  await step($, 'agent-1')

  // 타입 12·설명 16: 폭 44면 토큰까지 넣을 자리(설명 5칸)는 없고, 호출 수까지는 들어간다(설명 12칸).
  const narrow = { ...PANE_TARGET.props, bodyColumns: 44 }
  const ui = await $.ui.mount({ ...PANE_TARGET, props: narrow, surface: 'terminal' })
  const row = await rowOf(ui, 'backend-impl')
  expect(row).toContain('0회')
  expect(row).not.toContain('116')
})
```

- [ ] **Step 2: 테스트 실행**

Run: `claude plugin test mods/agent-monitor`
Expected: PASS (30 pass). 실패하면 태스크 3의 렌더 훅만 고친다(이 태스크의 테스트가 화면 규칙의 기준이다).

---

### Task 5: `/agent-monitor` 명령

**Files:**
- Modify: `mods/agent-monitor/hooks/register.tsx`(`register` 안에 `command.run` 훅 추가, `agents` import에 `counts`·`HELP`·`parseKeep` 추가)
- Test: `mods/agent-monitor/tests/agent-monitor.test.tsx`

**Interfaces:**
- Consumes: `counts`, `HELP`, `parseKeep`, `trim`, 상태 atom, `STORE_KEEP`, `STORE_AUTO`
- Produces: `command.run { command: 'agent-monitor' }` 훅

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
function runCommand(args: string) {
  return {
    command: 'agent-monitor',
    args,
    origin: { kind: 'composer' as const },
    presentation: { isFullscreen: false, columns: 80 },
  }
}

test('/agent-monitor는 패널을 열고 개수를 알려 준다', async ($, on) => {
  mock.clock(on)
  mock.store(on, { auto: false })
  const world = engine(on)
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  await $.agent.spawn(SPAWN)
  expect(world.opened).toEqual([])

  expect((await $.command.run(runCommand(''))).text).toBe('서브에이전트 패널을 열었어요 (실행 중 1 · 완료 0)')
  expect(world.opened).toEqual(['agent-monitor'])
})

test('/agent-monitor keep은 즉시 자르고 실행 중 기록은 남긴다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  engine(on)
  for (const n of [1, 2, 3, 4]) {
    await $.agent.spawn({ ...SPAWN, tool_use_id: `tu-${n}` })
    await clock.advance(1_000)
  }
  for (const n of [1, 2, 3]) {
    await complete($, `agent-${n}`, 'answer')
    await clock.advance(1_000)
  }

  expect((await $.command.run(runCommand('keep 1'))).text).toBe('끝난 에이전트를 최근 1개까지 남겨요.')
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.findAll({ type: 'Text', text: '✓' })).toHaveLength(1)
  expect(await ui.findAll({ type: 'Text', text: '●' })).toHaveLength(1)
})

test('/agent-monitor clear는 끝난 기록만 지운다', async ($, on) => {
  mock.clock(on)
  engine(on)
  await $.agent.spawn(SPAWN)
  await $.agent.spawn({ ...SPAWN, tool_use_id: 'tu-2' })
  await complete($, 'agent-1', 'answer')

  expect((await $.command.run(runCommand('clear'))).text).toBe('끝난 기록을 지웠어요.')
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '✓' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: '●' })).toBeDefined()
})

test('/agent-monitor auto off면 자동으로 열지 않는다', async ($, on) => {
  mock.clock(on)
  mock.store(on)
  const world = engine(on)
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })

  expect((await $.command.run(runCommand('auto off'))).text).toBe('패널 자동 열기를 껐어요.')
  await $.agent.spawn(SPAWN)
  expect(world.opened).toEqual([])
  expect((await $.command.run(runCommand('auto on'))).text).toBe('패널 자동 열기를 켰어요.')
})

test('잘못된 인자는 사용법을 알려 준다', async ($, on) => {
  mock.clock(on)
  mock.store(on)
  engine(on)
  expect((await $.command.run(runCommand('keep 0'))).text).toBe('보관 개수는 1~50 사이 정수예요.')
  expect((await $.command.run(runCommand('keep'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('auto maybe'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('clear now'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('foo'))).text).toBe(HELP)
})

test('설정은 세션이 바뀌어도 유지되고, 깨진 값은 기본값으로 읽는다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on, { keep: 0 })
  engine(on)
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  // 깨진 keep(0) → 기본 10: 끝난 기록 11개 중 10개만 남는다.
  for (let n = 1; n <= 11; n++) {
    await $.agent.spawn({ ...SPAWN, tool_use_id: `tu-${n}` })
    await clock.advance(1_000)
    await complete($, `agent-${n}`, 'answer')
  }
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.findAll({ type: 'Text', text: '✓' })).toHaveLength(10)

  await $.command.run(runCommand('keep 3'))
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  await $.agent.spawn({ ...SPAWN, tool_use_id: 'tu-12' })
  await complete($, 'agent-12', 'answer')
  expect(await ui.findAll({ type: 'Text', text: '✓' })).toHaveLength(3)
})
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: FAIL, 명령 테스트 6개 실패(`command.run` 훅 없음)

- [ ] **Step 3: 구현**

`register.tsx`의 `agents` import에 `counts`, `HELP`, `parseKeep`을 추가하고, `register` 안 `ui.render` 훅 위에 추가:

```tsx
  on('command.run', { command: 'agent-monitor' }, async ($, e) => {
    const [sub = '', ...rest] = e.args.trim().toLowerCase().split(/\s+/)

    if (sub === '' && rest.length === 0) {
      await $.ui.open({ id: PANE, title: TITLE })
      const { running, ended } = counts(await read($, records))
      return { text: `서브에이전트 패널을 열었어요 (실행 중 ${running} · 완료 ${ended})` }
    }
    if (sub === 'keep') {
      const parsed = parseKeep(rest.join(' '))
      if (typeof parsed === 'string') return { text: parsed }
      await update($, keep, () => parsed)
      await $.store.set(STORE_KEEP, parsed)
      await update($, records, list => trim(list, parsed))
      return { text: `끝난 에이전트를 최근 ${parsed}개까지 남겨요.` }
    }
    if (sub === 'clear' && rest.length === 0) {
      await update($, records, list => list.filter(r => r.status === 'running'))
      return { text: '끝난 기록을 지웠어요.' }
    }
    if (sub === 'auto' && rest.length === 1 && (rest[0] === 'on' || rest[0] === 'off')) {
      const isOn = rest[0] === 'on'
      await update($, auto, () => isOn)
      await $.store.set(STORE_AUTO, isOn)
      return { text: isOn ? '패널 자동 열기를 켰어요.' : '패널 자동 열기를 껐어요.' }
    }
    return { text: HELP }
  })
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run: `claude plugin test mods/agent-monitor`
Expected: PASS (36 pass)

Run: `claude plugin validate mods/agent-monitor`
Expected: `Validation passed`. `command.run`이 `answers its own command:`로 표시된다.

---

### Task 6: 문서와 실제 세션 확인

**Files:**
- Modify: `mods/README.md`(끝에 절 추가), `README.md:68` 아래(모드 표 한 줄)

**Interfaces:**
- Consumes: 태스크 1~5의 동작
- Produces: 없음

- [ ] **Step 1: `mods/README.md`에 절 추가**

파일 끝에:

````markdown
## agent-monitor

이 세션에서 띄운 서브에이전트를 사이드 패널에 실시간으로 보여줍니다.

```
서브에이전트 · 실행 중 2 · 완료 1

● backend-impl       주문 서비스 구현      2m 14s   18회  42.1k
● boundary-verifier  경계면 검증             48s    7회  11.3k
✓ Explore            결제 모듈 탐색          21s    5회   6.2k
```

- 기호: `●` 실행 중 · `✓` 완료 · `✗` 실패 · `■` 중단
- 열: 타입 · 작업 설명 · 경과(끝난 건 총 소요) · 도구 호출 수 · 토큰
- 토큰은 그 에이전트의 모든 요청에서 입력·출력·캐시 생성·캐시 읽기를 더한 처리 총량이다. 비용이 아니다
- 실행 중인 에이전트가 있는 동안 1초마다 갱신한다. 없으면 갱신하지 않는다
- 세션에서 첫 서브에이전트가 뜰 때 패널을 한 번 자동으로 연다. 닫은 뒤에는 `/agent-monitor`로 연다
- 좁으면 작업 설명을 줄이고, 그래도 모자라면 토큰, 호출 수 순으로 뺀다
- 끝난 에이전트는 최근 10개(설정 가능)만 남긴다. `/clear`·resume에서 비운다
- `/agent-monitor`: 패널 열기
- `/agent-monitor keep <1~50>`: 끝난 에이전트 보관 개수 (세션 간 유지)
- `/agent-monitor clear`: 끝난 기록 지우기
- `/agent-monitor auto on|off`: 자동 열기 켜기/끄기 (세션 간 유지)
- 패널은 넓은 풀스크린 터미널·Desktop에서는 옆에, 그 외에는 입력창 위 프레임으로 그려진다. VS Code 확장에서는 그려지지 않는다
````

- [ ] **Step 2: 루트 `README.md` 모드 표에 한 줄 추가**

`| \`context-bar\` | …` 줄 바로 아래:

```markdown
| `agent-monitor` | 서브에이전트의 상태·경과 시간·도구 호출 수·토큰을 사이드 패널에 실시간 표시 ([mods/README.md](mods/README.md)) |
```

- [ ] **Step 3: 전체 검증**

Run: `claude plugin test mods/agent-monitor && claude plugin validate mods/agent-monitor`
Expected: 36 pass, `Validation passed`

Run: `claude plugin test mods/context-bar && claude plugin test mods/next-steps`
Expected: 기존 모드 테스트 모두 PASS(회귀 없음)

- [ ] **Step 4: 실제 세션에서 확인**

테스트 키트는 엔진 응답을 흉내 낼 뿐이라, 실제 엔진에서 `agent.spawn`의 `next(e)`가 **에이전트 시작 시점에** 돌아오는지 확인한다(끝날 때 돌아오면 그 사이의 도구 호출이 기록보다 먼저 와서 세지지 않는다).

Run: `claude --plugin-dir mods/agent-monitor`
그 세션에서 `Explore 에이전트로 이 저장소의 mods 폴더 구조를 조사해 줘`를 보낸다.
Expected: 에이전트가 뜨자마자 패널이 열리고 `● Explore` 행의 경과 시간과 호출 수가 실행 중에 오른다. 끝나면 `✓`로 바뀐다.
호출 수가 실행 중에 0으로 멈춰 있다가 끝나야 오른다면 멈추고 사용자에게 보고한다(설계 변경 필요: `tool.call`에서 모르는 `agentId`를 만나면 `$.agent.list()`로 기록을 만드는 방식).

- [ ] **Step 5: 커밋(사용자 확인 후)**

사용자에게 변경 요약을 보여주고 커밋 여부를 묻는다. 승인되면:

```bash
git add mods/agent-monitor mods/README.md README.md
git commit -m "feat(mods): 서브에이전트 상태를 보여주는 사이드 패널 agent-monitor 추가"
```

계획 문서 `docs/plans/2026-10-11-agent-monitor.md`는 사용자가 요청하면 별도 커밋(`docs: agent-monitor 구현 계획 추가`)으로 넣는다.
