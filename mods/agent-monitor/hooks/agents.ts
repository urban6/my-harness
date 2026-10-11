import type { AgentRecord, AgentStatus } from '../types'

export type SpawnInfo = { subagentType: string; description: string; parentAgentId?: string; workflow?: unknown }
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
    ...(input.workflow ? { isUnlisted: true as const } : {}),
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

// now는 목록을 받기 직전의 시각이다. 그 뒤에 시작한 기록은 목록에 아직 없을 수 있다.
export function reconcile(records: AgentRecord[], listed: ListedAgent[], now: number): AgentRecord[] {
  const byId = new Map(listed.map(a => [a.id, a.status]))
  return records.map(r => {
    if (r.status !== 'running') return r
    const status = byId.get(r.id)
    if (status === undefined) {
      const isGone = !r.isUnlisted && r.startedAt < now
      return isGone ? { ...r, status: 'completed', endedAt: now } : r
    }
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

const isWide = (ch: string) => /[\u1100-\u11ff\u3130-\u318f\uac00-\ud7a3]/.test(ch)

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
