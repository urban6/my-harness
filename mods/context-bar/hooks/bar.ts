import type { SessionContextBreakdown } from 'claude-code'

import type { Segment, Snapshot, Warn } from '../types'

export const DEFAULT_WARN: Warn = { yellow: 70, red: 85 }
export const HELP = '사용법: /context-bar [on|off|warn <노랑> <빨강>]'
const WARN_RULE = '경고 기준은 0 < 노랑 < 빨강 ≤ 100 인 정수여야 해요. 예: /context-bar warn 70 85'

const MIN_WIDTH = 10
const MAX_WIDTH = 30
// 막대 뒤 "  104% · 압축까지 999k" 글자와 여유 칸.
const LABEL_COLUMNS = 24
const ORDER = { used: 0, free: 1, buffer: 2 } as const

export type Cell = Segment & { cells: number }

export function barWidth(columns: number): number {
  return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, columns - LABEL_COLUMNS))
}

// prev는 직전 측정값. 없으면(첫 측정·압축 직후) 증가량을 남기지 않는다.
export function toSnapshot(b: SessionContextBreakdown, prev: Snapshot | null): Snapshot {
  const segments: Segment[] = b.categories
    .filter(c => c.kind !== 'deferred')
    .map(c => ({ kind: c.kind as Segment['kind'], name: c.name, color: c.color, tokens: c.tokens }))
    // 압축 버퍼는 남은 공간 뒤, 막대 끝에 둔다. 같은 kind끼리는 받은 순서를 지킨다.
    .sort((x, y) => ORDER[x.kind] - ORDER[y.kind])
  return {
    segments,
    totalTokens: b.totalTokens,
    maxTokens: b.rawMaxTokens,
    percent: b.percentage,
    compactAt: b.isAutoCompactEnabled ? (b.autoCompactThreshold ?? null) : null,
    delta: prev === null ? null : b.totalTokens - prev.totalTokens,
  }
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

  // 최대 나머지 방식: 내림한 뒤 남은 칸을 소수부가 큰 구간부터 하나씩 준다.
  // 구간마다 1칸씩 보장하면 작은 구간이 많을 때 사용 칸이 실제보다 부푼다.
  const exact = shown.map(s => (s.tokens / total) * width)
  const cells = exact.map(Math.floor)
  const byRemainder = exact.map((_, i) => i).sort((a, b) => (exact[b]! % 1) - (exact[a]! % 1))
  let left = width - cells.reduce((sum, n) => sum + n, 0)
  for (const i of byRemainder) {
    if (left === 0) break
    cells[i] = cells[i]! + 1
    left -= 1
  }

  // 쓰고 있는 게 있으면 1칸은 보이게 한다. 그 칸은 가장 큰 구간에서 뺀다.
  const used = shown.flatMap((s, i) => (s.kind === 'used' ? [i] : []))
  if (used.length > 0 && used.every(i => cells[i] === 0)) {
    const give = used.reduce((a, b) => (shown[b]!.tokens > shown[a]!.tokens ? b : a))
    const take = largest(cells)
    cells[take] = cells[take]! - 1
    cells[give] = 1
  }
  return shown.map((s, i) => ({ ...s, cells: cells[i]! })).filter(c => c.cells > 0)
}

// 경고 색의 기준 비율. 자동 압축이 켜져 있으면 압축 시점 대비, 꺼져 있으면 창 대비.
export function warnPercent(snap: Snapshot): number {
  if (snap.compactAt === null || snap.compactAt <= 0) return snap.percent
  return Math.round((snap.totalTokens / snap.compactAt) * 100)
}

export function capacityLabel(snap: Snapshot): string {
  if (snap.compactAt === null) return `${formatTokens(snap.totalTokens)} / ${formatTokens(snap.maxTokens)}`
  return `압축까지 ${formatTokens(Math.max(0, snap.compactAt - snap.totalTokens))}`
}

export function formatDelta(n: number | null): string | null {
  if (n === null || n === 0) return null
  return `${n > 0 ? '+' : '-'}${formatTokens(Math.abs(n))}`
}

export function largestUsed(segments: Segment[]): Segment | undefined {
  return segments
    .filter(s => s.kind === 'used' && s.tokens > 0)
    .reduce<Segment | undefined>((best, s) => (best === undefined || s.tokens > best.tokens ? s : best), undefined)
}

// 터미널 칸 수. 한글은 두 칸을 차지한다.
export function displayWidth(text: string): number {
  return [...text].reduce((n, ch) => n + (/[\u1100-\u11ff\u3130-\u318f\uac00-\ud7a3]/.test(ch) ? 2 : 1), 0)
}

export type Extras = { top?: Segment; delta?: string }

// 막대와 비율·용량 뒤에 남는 칸에 덧붙일 것. 좁으면 증가량, 그다음 카테고리 이름 순으로 뺀다.
export function extras(snap: Snapshot, columns: number, barCells: number): Extras {
  let room = columns - barCells - displayWidth(`  ${snap.percent}% · ${capacityLabel(snap)}`)
  const top = largestUsed(snap.segments)
  if (top === undefined) return {}
  const topWidth = displayWidth(` · ${top.name} ${formatTokens(top.tokens)}`)
  if (room < topWidth) return {}
  room -= topWidth

  const delta = formatDelta(snap.delta)
  if (delta === null || room < displayWidth(` · ${delta}`)) return { top }
  return { top, delta }
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

// 앱이 명령 이름(context-bar:)을 앞에 붙이므로 여기서는 쓰지 않는다.
export function describe(enabled: boolean, warn: Warn, snap: Snapshot | null): string {
  const head = `${enabled ? '켜짐' : '꺼짐'} · 경고 ${warn.yellow}% / ${warn.red}% · 마지막 측정`
  if (snap === null) return `${head} 아직 없음`

  const compact = snap.compactAt === null ? '' : ` · ${capacityLabel(snap)}`
  const parts = snap.segments
    .filter(s => s.kind === 'used' && s.tokens > 0)
    .sort((a, b) => b.tokens - a.tokens)
    .map(s => `${s.name} ${formatTokens(s.tokens)}`)
  const makeup = parts.length === 0 ? '' : `\n구성: ${parts.join(' · ')}`
  return `${head} ${snap.percent}% (${formatTokens(snap.totalTokens)} / ${formatTokens(snap.maxTokens)})${compact}${makeup}`
}
