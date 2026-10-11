import type { UiSettings, Usage } from '../types'

export const DEFAULT_UI: UiSettings = { count: 4, bold: true, gap: 1, tokens: true }
// 항목 단축키가 숫자 한 자리(1~9)라서 9개까지.
export const MAX_COUNT = 9
export const MAX_GAP = 2

// count는 상한일 뿐이다. 모델은 "최대 N개"를 꽉 채우려 해서 채우지 말라고 따로 못 박는다.
export const askFor = (count: number) => `지금까지의 대화를 바탕으로, 사용자가 바로 이어서 요청할 만한 다음 작업을 제안해. 많아야 ${count}개.
- 확실히 유용한 것만 넣는다. 개수를 채우려고 만들지 말 것. 1~2개만 유용하면 1~2개만 낸다.
- 유용하다는 건 방금 대화의 흐름에서 사용자가 실제로 다음에 할 법한 일이라는 뜻이다. 막연한 일반론(문서화·테스트·리팩터링 등)을 대화와 상관없이 덧붙이지 않는다.
- 각 항목은 사용자가 그대로 AI에게 보낼 수 있는 한 문장짜리 요청문(명령형)으로, 60자 안팎.
- 대화에서 쓰인 언어로 쓴다.
- 서로 겹치지 않게, 가치가 큰 순서로.
- 이어서 할 일이 없거나 대화가 마무리됐으면 빈 배열.
JSON 문자열 배열 하나만 출력하고 다른 말은 하지 마.`

// 모델 답에서 첫 JSON 배열을 꺼낸다. 코드 펜스나 앞뒤 군말이 붙어도 견딘다.
export function parseSteps(text: string, max = DEFAULT_UI.count): string[] {
  const found = text.match(/\[[\s\S]*\]/)
  if (!found) return []
  try {
    const value: unknown = JSON.parse(found[0])
    if (!Array.isArray(value)) return []
    return value
      .filter((step): step is string => typeof step === 'string')
      .map(step => step.trim())
      .filter(step => step.length > 0)
      .slice(0, max)
  } catch {
    return []
  }
}

export function composePrompt(steps: string[]): string {
  if (steps.length === 1) return steps[0] ?? ''
  return ['다음을 순서대로 진행해 줘:', ...steps.map((step, i) => `${i + 1}. ${step}`)].join('\n')
}

export function toggle(picked: number[], index: number): number[] {
  return picked.includes(index)
    ? picked.filter(i => i !== index)
    : [...picked, index].sort((a, b) => a - b)
}

export const NO_USAGE: Usage = {
  input_tokens: 0,
  output_tokens: 0,
  cache_read_input_tokens: 0,
  cache_creation_input_tokens: 0,
}

export function addUsage(a: Usage, b: Usage): Usage {
  return {
    input_tokens: a.input_tokens + b.input_tokens,
    output_tokens: a.output_tokens + b.output_tokens,
    cache_read_input_tokens: a.cache_read_input_tokens + b.cache_read_input_tokens,
    cache_creation_input_tokens: a.cache_creation_input_tokens + b.cache_creation_input_tokens,
  }
}

export function formatUsage(u: Usage): string {
  const n = (v: number) => v.toLocaleString('en-US')
  return `입력 ${n(u.input_tokens)} · 캐시 읽기 ${n(u.cache_read_input_tokens)} · 캐시 쓰기 ${n(u.cache_creation_input_tokens)} · 출력 ${n(u.output_tokens)}`
}

// fork 모드에서 컨텍스트가 이보다 크면 자동 제안을 건너뛴다. 0이면 건너뛰지 않는다.
export const DEFAULT_LIMIT = 100_000

const PER_MESSAGE_CHARS = 2_000
const TRANSCRIPT_CHARS = 24_000

// Haiku에 보낼 최근 대화. 뒤에서부터 메시지당·전체 글자 수를 잘라 비용을 일정하게 둔다.
export function recentTranscript(messages: readonly { role: 'user' | 'assistant'; text: string }[]): string {
  const lines: string[] = []
  let size = 0
  for (const message of [...messages].reverse()) {
    const text = message.text.trim()
    if (text === '') continue
    const cut = text.length > PER_MESSAGE_CHARS ? `${text.slice(0, PER_MESSAGE_CHARS)}…` : text
    const line = `${message.role === 'user' ? '사용자' : 'AI'}: ${cut}`
    if (size + line.length > TRANSCRIPT_CHARS) break
    lines.unshift(line)
    size += line.length
  }
  return lines.join('\n\n')
}

export function formatTokens(n: number): string {
  if (n < 1_000) return String(n)
  return `${(n / 1_000).toFixed(n < 10_000 ? 1 : 0)}k`
}

// 밴드 한 줄용: 보낸 토큰 전체(그중 캐시 읽기)와 받은 토큰.
export function compactUsage(u: Usage): string {
  const sent = u.input_tokens + u.cache_read_input_tokens + u.cache_creation_input_tokens
  const cached = u.cache_read_input_tokens > 0 ? ` (캐시 ${formatTokens(u.cache_read_input_tokens)})` : ''
  return `입력 ${formatTokens(sent)}${cached} · 출력 ${formatTokens(u.output_tokens)}`
}

// $.store에서 읽은 값을 믿지 않고 범위 안의 설정으로 맞춘다.
export function normalizeUi(value: unknown): UiSettings {
  const v = (typeof value === 'object' && value !== null ? value : {}) as Partial<Record<keyof UiSettings, unknown>>
  const int = (x: unknown, min: number, max: number, fallback: number) =>
    typeof x === 'number' && Number.isInteger(x) && x >= min && x <= max ? x : fallback
  return {
    count: int(v.count, 1, MAX_COUNT, DEFAULT_UI.count),
    bold: typeof v.bold === 'boolean' ? v.bold : DEFAULT_UI.bold,
    gap: int(v.gap, 0, MAX_GAP, DEFAULT_UI.gap),
    tokens: typeof v.tokens === 'boolean' ? v.tokens : DEFAULT_UI.tokens,
  }
}

// `/next-steps ui <key> <value>` 한 건을 적용한다. 값이 틀리면 이유 문자열을 돌려준다.
export function applyUi(ui: UiSettings, key: string, value: string): UiSettings | string {
  const onOff = value === 'on' ? true : value === 'off' ? false : undefined
  const n = Number(value)
  switch (key) {
    case 'count':
      return value !== '' && Number.isInteger(n) && n >= 1 && n <= MAX_COUNT ? { ...ui, count: n } : `count는 1~${MAX_COUNT} 사이 정수예요.`
    case 'gap':
      return value !== '' && Number.isInteger(n) && n >= 0 && n <= MAX_GAP ? { ...ui, gap: n } : `gap은 0~${MAX_GAP} 사이 정수예요.`
    case 'bold':
      return onOff === undefined ? 'bold는 on 또는 off예요.' : { ...ui, bold: onOff }
    case 'tokens':
      return onOff === undefined ? 'tokens는 on 또는 off예요.' : { ...ui, tokens: onOff }
    default:
      return `바꿀 수 있는 항목은 count·bold·gap·tokens예요. 글자 크기는 앱(Cmd +)이나 터미널 글꼴 설정으로 바꿔 주세요.`
  }
}

export function formatUi(ui: UiSettings): string {
  return `항목 수 ${ui.count} · 굵게 ${ui.bold ? 'on' : 'off'} · 간격 ${ui.gap} · 토큰 줄 ${ui.tokens ? 'on' : 'off'}`
}
