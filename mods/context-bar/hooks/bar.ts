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
