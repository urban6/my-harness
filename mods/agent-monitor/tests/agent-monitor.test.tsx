import { expect, mock, test } from 'claude-code/testing'
import type { AgentInfo, AgentSpawnInput, EngineInterface, On } from 'claude-code'

import type { AgentRecord } from '../types'
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
  // 목록을 만든 뒤 돌려주기 전에 끼어드는 일. 목록을 읽는 사이에 생긴 변화를 흉내 낸다.
  duringList?: () => Promise<void>
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
  on('agent.list', async () => {
    if (world.listFails) throw new Error('boom')
    const snapshot = world.listed ?? world.spawned.map(id => running(id))
    const during = world.duringList
    world.duringList = undefined
    if (during) await during()
    return { value: snapshot }
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

test('목록을 읽는 사이에 뜬 에이전트는 완료로 바꾸지 않는다', async ($, on) => {
  const clock = mock.clock(on)
  const world = engine(on)
  await $.agent.spawn(SPAWN)
  // 틱이 목록을 받는 동안 응답을 붙잡아 두고, 그사이에 새 에이전트를 띄운다.
  let entered!: () => void
  let release!: () => void
  const listing = new Promise<void>(r => (entered = r))
  const gate = new Promise<void>(r => (release = r))
  world.duringList = async () => {
    entered()
    await gate
  }
  const ticking = clock.advance(1_000)
  await listing
  await $.agent.spawn({ ...SPAWN, tool_use_id: 'tu-2', subagentType: 'Explore', description: '탐색' })
  release()
  await ticking
  await clock.advance(1_000)
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })
  expect(await ui.findAll({ type: 'Text', text: '●' })).toHaveLength(2)
  expect(await ui.find({ type: 'Text', text: '✓' })).toBeUndefined()
})

test('워크플로 에이전트는 엔진 목록에 없어도 종료 이벤트가 올 때까지 실행 중이다', async ($, on) => {
  const clock = mock.clock(on)
  const world = engine(on)
  // 워크플로의 에이전트는 $.agent.list()에 나오지 않는다.
  world.listed = []
  await $.agent.spawn({ ...SPAWN, workflow: { runId: 'wf-1', agentIndex: 0 } })
  const ui = await $.ui.mount({ ...PANE_TARGET, surface: 'terminal' })

  await clock.advance(3_000)
  expect(await ui.find({ type: 'Text', text: '●' })).toBeDefined()
  expect(await rowOf(ui, 'backend-impl')).toContain('3s')

  await complete($, 'agent-1', 'answer')
  expect(await ui.find({ type: 'Text', text: '✓' })).toBeDefined()
})
