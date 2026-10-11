import { expect, mock, test } from 'claude-code/testing'
import type { ContextCategory, On, SessionContextBreakdown, SessionMeasureInput } from 'claude-code'

import {
  barWidth,
  capacityLabel,
  DEFAULT_WARN,
  describe,
  displayWidth,
  extras,
  formatDelta,
  formatTokens,
  HELP,
  layout,
  normalizeWarn,
  parseWarn,
  percentColor,
  toSnapshot,
  warnPercent,
} from '../hooks/bar'

function category(name: string, kind: ContextCategory['kind'], tokens: number, color = 'inactive'): ContextCategory {
  return { name, kind, tokens, color, isDeferred: kind === 'deferred' }
}

function breakdownOf(
  categories: ContextCategory[],
  totalTokens: number,
  percentage: number,
  // null이면 자동 압축이 꺼진 세션
  autoCompactThreshold: number | null = 167_000,
): SessionContextBreakdown {
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
    ...(autoCompactThreshold === null ? {} : { autoCompactThreshold }),
    isAutoCompactEnabled: autoCompactThreshold !== null,
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

const seg = (kind: 'used' | 'free' | 'buffer', name: string, tokens: number) => ({
  kind,
  name,
  color: kind === 'used' ? 'claude' : 'inactive',
  tokens,
})

test('breakdown에서 창 밖(deferred) 구간을 빼고 그릴 값만 남긴다', async () => {
  expect(toSnapshot(NORMAL, null)).toEqual({
    segments: [
      { kind: 'used', name: 'System prompt', color: 'promptBorder', tokens: 20_000 },
      { kind: 'used', name: 'Messages', color: 'permission', tokens: 104_000 },
      { kind: 'free', name: 'Free space', color: 'inactive', tokens: 43_000 },
      { kind: 'buffer', name: 'Autocompact buffer', color: 'inactive', tokens: 33_000 },
    ],
    totalTokens: 124_000,
    maxTokens: 200_000,
    percent: 62,
    compactAt: 167_000,
    delta: null,
  })
})

test('압축 버퍼는 받은 순서와 상관없이 막대 끝에 둔다', async () => {
  const shuffled = breakdownOf(
    [
      category('Autocompact buffer', 'buffer', 33_000),
      category('System prompt', 'used', 20_000, 'promptBorder'),
      category('Free space', 'free', 43_000),
      category('Messages', 'used', 104_000, 'permission'),
    ],
    124_000,
    62,
  )
  expect(toSnapshot(shuffled, null).segments.map(s => s.name)).toEqual([
    'System prompt',
    'Messages',
    'Free space',
    'Autocompact buffer',
  ])
})

test('자동 압축이 꺼져 있으면 압축 시점이 없다', async () => {
  const off = breakdownOf(NORMAL.categories, 124_000, 62, null)
  expect(toSnapshot(off, null).compactAt).toBeNull()
})

test('직전 측정이 있으면 이번 턴 증가량을 남긴다', async () => {
  const prev = toSnapshot(breakdownOf(NORMAL.categories, 118_000, 59), null)
  expect(toSnapshot(NORMAL, prev).delta).toBe(6_000)
  expect(formatDelta(6_000)).toBe('+6k')
  expect(formatDelta(-2_400)).toBe('-2k')
  expect(formatDelta(800)).toBe('+800')
  expect(formatDelta(0)).toBeNull()
  expect(formatDelta(null)).toBeNull()
})

test('토큰 비율로 칸을 나누고 합계는 항상 막대 폭이다', async () => {
  const { segments } = toSnapshot(NORMAL, null)
  expect(layout(segments, 30).map(c => c.cells)).toEqual([3, 16, 6, 5])
  expect(sumCells(layout(segments, 30))).toBe(30)

  // 사용량이 아주 작아도 막대에 1칸은 보인다. 그 칸은 가장 큰 구간에서 뺀다.
  const tiny = layout([seg('used', 'a', 1), seg('free', 'Free space', 999)], 10)
  expect(tiny.map(c => c.cells)).toEqual([1, 9])

  // 반올림 오차(3+3+3=9)도 채운다.
  const thirds = layout([seg('used', 'a', 1), seg('used', 'b', 1), seg('used', 'c', 1)], 10)
  expect(sumCells(thirds)).toBe(10)

  // 0 토큰 구간은 그리지 않고, 전부 0이면 빈 막대.
  expect(layout([seg('used', 'a', 0), seg('free', 'Free space', 5)], 10)).toEqual([{ ...seg('free', 'Free space', 5), cells: 10 }])
  expect(layout([seg('free', 'Free space', 0)], 10)).toEqual([])
})

test('작은 구간이 많아도 사용 칸이 실제 비율보다 부풀지 않는다', async () => {
  // 1M 창에 8k짜리 6개(4.8%): 30칸 중 1.44칸. 구간마다 1칸씩 주면 6칸(20%)이 된다.
  const segments = [
    ...['a', 'b', 'c', 'd', 'e', 'f'].map(name => seg('used', name, 8_000)),
    seg('free', 'Free space', 919_000),
    seg('buffer', 'Autocompact buffer', 33_000),
  ]
  const cells = layout(segments, 30)
  expect(sumCells(cells)).toBe(30)
  expect(sumCells(cells.filter(c => c.kind === 'used'))).toBe(1)
  // 0칸이 된 구간은 결과에서 빠진다.
  expect(cells.every(c => c.cells > 0)).toBe(true)
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

test('경고 색은 자동 압축 시점 대비, 압축이 꺼져 있으면 창 대비로 정한다', async () => {
  // 124k / 압축 167k = 74%
  expect(warnPercent(toSnapshot(NORMAL, null))).toBe(74)
  expect(warnPercent(toSnapshot(breakdownOf(NORMAL.categories, 124_000, 62, null), null))).toBe(62)
})

test('압축이 켜져 있으면 압축까지 남은 양을, 꺼져 있으면 사용량 / 창을 쓴다', async () => {
  expect(capacityLabel(toSnapshot(NORMAL, null))).toBe('압축까지 43k')
  // 압축 시점을 넘겨도 음수로 쓰지 않는다.
  expect(capacityLabel(toSnapshot(breakdownOf(NORMAL.categories, 170_000, 85), null))).toBe('압축까지 0')
  expect(capacityLabel(toSnapshot(breakdownOf(NORMAL.categories, 124_000, 62, null), null))).toBe('124k / 200k')
})

test('한글은 두 칸으로 센다', async () => {
  expect(displayWidth('62%')).toBe(3)
  expect(displayWidth('압축까지 43k')).toBe(12)
})

test('폭이 남을 때만 가장 큰 카테고리와 증가량을 붙이고, 좁으면 증가량부터 뺀다', async () => {
  const prev = toSnapshot(breakdownOf(NORMAL.categories, 118_000, 59), null)
  const snap = toSnapshot(NORMAL, prev)
  // 막대 30 + "  62% · 압축까지 43k"(20) = 50. 남은 칸에 " · Messages 104k"(16), " · +6k"(6)
  expect(extras(snap, 80, 30)).toEqual({ top: snap.segments[1], delta: '+6k' })
  expect(extras(snap, 70, 30)).toEqual({ top: snap.segments[1] })
  expect(extras(snap, 60, 30)).toEqual({})
  // 직전 측정이 없으면 증가량은 없다.
  expect(extras(toSnapshot(NORMAL, null), 120, 30)).toEqual({ top: snap.segments[1] })
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

test('현재 설정과 마지막 측정의 구성을 요약한다', async () => {
  // 앱이 명령 이름(context-bar:)을 앞에 붙이므로 직접 쓰지 않는다.
  expect(describe(true, DEFAULT_WARN, null)).toBe('켜짐 · 경고 70% / 85% · 마지막 측정 아직 없음')
  expect(describe(false, { yellow: 60, red: 90 }, toSnapshot(NORMAL, null))).toBe(
    '꺼짐 · 경고 60% / 90% · 마지막 측정 62% (124k / 200k) · 압축까지 43k\n구성: Messages 104k · System prompt 20k',
  )
  const off = toSnapshot(breakdownOf(NORMAL.categories, 124_000, 62, null), null)
  expect(describe(true, DEFAULT_WARN, off)).toBe(
    '켜짐 · 경고 70% / 85% · 마지막 측정 62% (124k / 200k)\n구성: Messages 104k · System prompt 20k',
  )
})

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

// 압축 입력·결과에는 메시지가 하나 이상 있어야 한다.
const MESSAGES = [{ role: 'user' as const, text: '회원가입 API 만들어 줘', toolUses: [] }]

// 엔진 쪽 응답. 모드가 next(e)로 넘기면 이 값들이 돌아온다.
function engine(on: On) {
  on('ui.render', { component: 'AbovePrompt' }, ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine</Text>
  })
  on('session.measure', ($, e) => ({ changed: e.changed }))
  on('session.compact', () => ({ messages: MESSAGES }))
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('command.register', ($, e) => ({ value: { command: e.name } }))
}

function usageOf(b: SessionContextBreakdown) {
  return { value: { startedAt: 0, context: { tokens: b.totalTokens, window: 200_000, percent: b.percentage, breakdown: b }, rateLimits: [] } }
}

// 비율 숫자 Text. Text의 key는 찾기 결과에 남지 않아 내용으로 찾는다.
const PERCENT = { type: 'Text', text: /^\d+%$/ }

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
  const percent = await ui.find(PERCENT)
  expect(percent?.text).toBe('62%')
  // 124k / 압축 167k = 74%라 노랑.
  expect(percent?.props.color).toBe('warning')
  expect(await ui.find({ type: 'Text', text: ' · 압축까지 43k' })).toBeDefined()
  expect(await barText(ui)).toBe('█'.repeat(19) + '░'.repeat(6) + '·'.repeat(5))
  const used = await ui.findAll({ type: 'Text', text: /^█+$/ })
  expect(used.map(t => t.props.color)).toEqual(['promptBorder', 'permission'])
  // 가장 큰 카테고리 이름은 막대와 같은 색으로 써서 범례 역할을 한다.
  expect((await ui.find({ type: 'Text', text: 'Messages' }))?.props.color).toBe('permission')
  expect(await ui.find({ type: 'Text', text: ' 104k' })).toBeDefined()
  // 첫 측정에는 증가량이 없다.
  expect(await ui.find({ type: 'Text', text: /^ · [+-]/ })).toBeUndefined()
  // 다른 모드·엔진이 그린 밴드는 막대 위에 남는다.
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('기준을 넘으면 비율 숫자 색이 바뀌고, 창을 넘어도 막대는 깨지지 않는다', async ($, on) => {
  engine(on)
  let current = breakdownOf(NORMAL.categories, 100_000, 50)
  on('session.usage', () => usageOf(current))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })

  // 100k / 압축 167k = 60%
  await $.session.measure(MEASURED)
  expect((await ui.find(PERCENT))?.props.color).toBeUndefined()

  // 창 대비 72%지만 압축 대비 86%라 빨강.
  current = breakdownOf(NORMAL.categories, 144_000, 72)
  await $.session.measure(MEASURED)
  expect((await ui.find(PERCENT))?.props.color).toBe('error')

  current = breakdownOf(
    [category('Messages', 'used', 208_000, 'permission'), category('Free space', 'free', 0)],
    208_000,
    104,
  )
  await $.session.measure(MEASURED)
  const percent = await ui.find(PERCENT)
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
  // 덧붙이는 정보는 들어갈 자리가 없으면 뺀다.
  expect(await ui.find({ type: 'Text', text: 'Messages' })).toBeUndefined()
})

test('턴이 끝날 때마다 직전 측정 대비 증가량을 보여준다', async ($, on) => {
  engine(on)
  let current = breakdownOf(NORMAL.categories, 118_000, 59)
  on('session.usage', () => usageOf(current))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })

  await $.session.measure(MEASURED)
  current = NORMAL
  await $.session.measure(MEASURED)
  expect(await ui.find({ type: 'Text', text: ' · +6k' })).toBeDefined()

  // 압축 뒤 첫 측정은 비교할 직전 값이 없다.
  await $.session.compact({ trigger: 'manual', messages: MESSAGES })
  current = breakdownOf(NORMAL.categories, 30_000, 15)
  await $.session.measure(MEASURED)
  expect(await ui.find({ type: 'Text', text: /^ · [+-]/ })).toBeUndefined()
})

test('자동 압축이 꺼져 있으면 사용량 / 창을 쓴다', async ($, on) => {
  engine(on)
  on('session.usage', () => usageOf(breakdownOf(NORMAL.categories, 124_000, 62, null)))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })

  await $.session.measure(MEASURED)
  expect(await ui.find({ type: 'Text', text: ' · 124k / 200k' })).toBeDefined()
  expect((await ui.find(PERCENT))?.props.color).toBeUndefined()
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
  expect((await ui.find(PERCENT))?.text).toBe('62%')

  mode = 'empty'
  await $.session.measure(MEASURED)
  expect((await ui.find(PERCENT))?.text).toBe('62%')
})

test('메인 대화가 압축되면 다음 측정까지 측정 전으로 돌아간다', async ($, on) => {
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)

  // 서브에이전트 압축과 미리 계산(precompute)은 메인 막대와 무관하다.
  await $.session.compact({ trigger: 'auto', agentId: 'agent-1', messages: MESSAGES })
  await $.session.compact({ trigger: 'precompute', messages: MESSAGES })
  expect((await ui.find(PERCENT))?.text).toBe('62%')

  await $.session.compact({ trigger: 'manual', messages: MESSAGES })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
})

test('압축이 건너뛰어지면 막대를 그대로 둔다', async ($, on) => {
  on('ui.render', { component: 'AbovePrompt' }, ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine</Text>
  })
  on('session.measure', ($, e) => ({ changed: e.changed }))
  on('session.compact', () => ({ skip: '막힘' }))
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)

  await $.session.compact({ trigger: 'manual', messages: MESSAGES })
  expect((await ui.find(PERCENT))?.text).toBe('62%')
})

test('설문이 떠 있으면 막대를 그리지 않는다', async ($, on) => {
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  await $.session.measure(MEASURED)

  const ui = await $.ui.mount({ ...BAND, props: { ...BAND.props, hasSurvey: true }, surface: 'terminal' })
  expect(await ui.find(PERCENT)).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('모드가 새로 로드되면(session.start) 이전 측정값을 버린다', async ($, on) => {
  mock.store(on)
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  await $.session.measure(MEASURED)

  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
})

test('/clear·resume로 대화가 바뀌면 이전 측정값을 버린다', async ($, on) => {
  engine(on)
  on('session.end', ($, e) => ({ sessionId: e.sessionId }))
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })

  // /clear에는 session.start가 오지 않고 session.end(clear)만 온다.
  await $.session.measure(MEASURED)
  await $.session.end({ reason: 'clear', sessionId: 's-1', resume: { id: 's-1' } })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()

  await $.session.measure(MEASURED)
  await $.session.end({ reason: 'resume', sessionId: 's-2', resume: { id: 's-2' } })
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
})

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
  expect(await ui.find(PERCENT)).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()

  // 꺼진 동안의 측정은 무시한다.
  await $.session.measure(MEASURED)
  await $.command.run(runCommand('on'))
  expect(await ui.find({ type: 'Text', text: '컨텍스트 측정 전' })).toBeDefined()
  await $.session.measure(MEASURED)
  expect((await ui.find(PERCENT))?.text).toBe('62%')
})

test('/context-bar warn으로 기준을 바꾸면 비율 색에 바로 반영된다', async ($, on) => {
  mock.store(on)
  engine(on)
  on('session.usage', () => usageOf(NORMAL))
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await $.session.measure(MEASURED)
  expect((await ui.find(PERCENT))?.props.color).toBe('warning')

  expect((await $.command.run(runCommand('warn 80 90'))).text).toBe('경고 기준: 80% 노랑 · 90% 빨강')
  expect((await ui.find(PERCENT))?.props.color).toBeUndefined()
  await $.command.run(runCommand('warn 50 60'))
  expect((await ui.find(PERCENT))?.props.color).toBe('error')
})

test('잘못된 인자는 받지 않고 사용법을 알려 준다', async ($, on) => {
  mock.store(on)
  engine(on)
  expect((await $.command.run(runCommand('warn 85 70'))).text).toContain('0 < 노랑 < 빨강 ≤ 100')
  expect((await $.command.run(runCommand('warn'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('foo'))).text).toBe(HELP)
  expect((await $.command.run(runCommand('on now'))).text).toBe(HELP)
  expect((await $.command.run(runCommand(''))).text).toBe('켜짐 · 경고 70% / 85% · 마지막 측정 아직 없음')
})

test('설정은 세션이 바뀌어도 유지되고, 깨진 값은 기본값으로 읽는다', async ($, on) => {
  mock.store(on, { enabled: false, warn: { yellow: 90, red: 50 } })
  engine(on)
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  expect((await $.command.run(runCommand(''))).text).toBe('꺼짐 · 경고 70% / 85% · 마지막 측정 아직 없음')

  await $.command.run(runCommand('on'))
  await $.command.run(runCommand('warn 60 90'))
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  expect((await $.command.run(runCommand(''))).text).toBe('켜짐 · 경고 60% / 90% · 마지막 측정 아직 없음')
})
