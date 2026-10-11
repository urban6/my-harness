import { expect, mock, test } from 'claude-code/testing'
import type { ModelCompleteResult, On, TurnCompleteInput } from 'claude-code'

import { applyUi, compactUsage, composePrompt, DEFAULT_UI, normalizeUi, parseSteps, recentTranscript } from '../hooks/steps'

const USAGE = {
  input_tokens: 0,
  output_tokens: 0,
  cache_creation_input_tokens: 0,
  cache_read_input_tokens: 0,
}

const TURN: TurnCompleteInput = {
  answer: '회원가입 API를 만들었어요.',
  durationMs: 1000,
  isAborted: false,
  turnId: 'turn-1',
  reason: 'answer',
}

const BAND = {
  plugin: 'next-steps',
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

// 엔진이 그릴 기본 밴드 자리. 모드가 next(e)로 넘기면 이게 그려진다.
function engineBand(on: On) {
  on('ui.render', { component: 'AbovePrompt' }, ($, e) => {
    const { Text } = $.ui.resolve(e)
    return <Text>engine</Text>
  })
}

// 기본(haiku)과 fork 어느 쪽으로 만들어도 같은 답을 준다.
function answerWith(on: On, reply: () => { value: ModelCompleteResult } | Promise<{ value: ModelCompleteResult }>) {
  on('model.fork', () => reply())
  on('model.complete', () => reply())
  on('session.messages', () => ({ value: [{ role: 'user' as const, text: '회원가입 API 만들어 줘', toolUses: [] }] }))
}

function answered(text: string) {
  return { value: { isAnswered: true as const, text, usage: USAGE } }
}

test('JSON 배열을 군말·코드 펜스 사이에서 꺼내고 4개로 자른다', async () => {
  expect(parseSteps('```json\n["a", " b ", "", 3, "c", "d", "e"]\n```')).toEqual(['a', 'b', 'c', 'd'])
  expect(parseSteps('제안 없음')).toEqual([])
})

test('여러 개를 고르면 번호 목록 하나로 합친다', async () => {
  expect(composePrompt(['테스트 추가'])).toBe('테스트 추가')
  expect(composePrompt(['테스트 추가', '문서화'])).toBe('다음을 순서대로 진행해 줘:\n1. 테스트 추가\n2. 문서화')
})

test('턴이 끝나면 제안을 띄우고, 고른 항목을 입력창에 채운 뒤 닫는다', async ($, on) => {
  const clock = mock.clock(on)
  let filled = ''
  engineBand(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => answered('["테스트 추가", "에러 응답 정리", "문서화"]'))
  on('prompt.fill', ($, e) => {
    filled = e.text
    return { isFilled: true, text: e.text, cursor: e.text.length }
  })

  await $.turn.complete(TURN)
  // fork는 턴 종료를 붙잡지 않도록 기다리지 않으므로, 끝까지 흘려 보낸다.
  await clock.settle()

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...BAND, surface })
    expect(await ui.findAll({ type: 'Button', text: /^\[ \]/ })).toHaveLength(3)
    await ui.unmount()
  }

  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  await ui.press({ key: 'step-2' })
  await ui.press({ key: 'step-0' })
  expect((await ui.find({ key: 'step-0' }))?.text).toContain('[x]')
  await ui.press({ key: 'fill' })

  expect(filled).toBe('다음을 순서대로 진행해 줘:\n1. 테스트 추가\n2. 문서화')
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('서브에이전트 턴과 중단된 턴에는 제안하지 않는다', async ($, on) => {
  const clock = mock.clock(on)
  let forks = 0
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => {
    forks += 1
    return answered('["x"]')
  })

  await $.turn.complete({ ...TURN, agentId: 'agent-1' })
  await $.turn.complete({ ...TURN, reason: 'aborted', isAborted: true })
  await clock.settle()

  expect(forks).toBe(0)
})

test('새 프롬프트가 나가면 늦게 도착한 제안은 버린다', async ($, on) => {
  const clock = mock.clock(on)
  engineBand(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, async () => {
    await clock.sleep(1000)
    return answered('["늦은 제안"]')
  })
  on('prompt.submit', ($, e) => ({ text: e.text }))

  await $.turn.complete(TURN)
  await clock.settle()
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '정리 중' })).toBeDefined()

  await $.prompt.submit({ text: '다른 일 해 줘', wait: false, origin: { kind: 'composer' } })
  await clock.advance(1000)

  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

function runCommand(args: string) {
  return {
    command: 'next-steps',
    args,
    origin: { kind: 'composer' as const },
    presentation: { isFullscreen: false, columns: 80 },
  }
}

test('제안을 기다리는 동안 "정리 중"을 띄운다', async ($, on) => {
  const clock = mock.clock(on)
  engineBand(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, async () => {
    await clock.sleep(1000)
    return answered('["테스트 추가"]')
  })

  await $.turn.complete(TURN)
  await clock.settle()
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '정리 중' })).toBeDefined()

  await clock.advance(1000)
  expect(await ui.find({ key: 'step-0' })).toBeDefined()
  expect(await ui.find({ key: 'fill' })).toBeDefined()
})

test('턴이 돌고 있거나 설문이 떠 있으면 목록을 숨긴다', async ($, on) => {
  const clock = mock.clock(on)
  engineBand(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => answered('["테스트 추가"]'))

  await $.turn.complete(TURN)
  await clock.settle()

  for (const props of [{ isWorking: true }, { hasSurvey: true }]) {
    const ui = await $.ui.mount({ ...BAND, surface: 'terminal', props: { ...BAND.props, ...props } })
    expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
    await ui.unmount()
  }
})

test('제안을 못 받으면 목록 없이 엔진 밴드로 돌아간다', async ($, on) => {
  const clock = mock.clock(on)
  engineBand(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => answered('지금은 제안할 게 없어요'))

  await $.turn.complete(TURN)
  await clock.settle()

  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('/next-steps는 바로 목록을 띄우고, off면 턴이 끝나도 제안하지 않는다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  engineBand(on)
  let forks = 0
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => {
    forks += 1
    return answered('["테스트 추가", "문서화"]')
  })

  await $.command.run(runCommand(''))
  await clock.settle()
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ key: 'step-1' })).toBeDefined()

  await $.command.run(runCommand('off'))
  await $.turn.complete(TURN)
  await clock.settle()
  expect(forks).toBe(1)
  expect(await ui.find({ type: 'Text', text: 'engine' })).toBeDefined()
})

test('모드가 다시 로드돼 제안이 끊겨도 session.start에서 이어서 띄운다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  engineBand(on)
  let forks = 0
  on('turn.complete', () => ({ text: '' }))
  on('command.register', ($, e) => ({ value: { command: e.name } }))
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  answerWith(on, async () => {
    forks += 1
    // 첫 요청은 다시 로드되며 끊긴 것처럼 돌아오지 않는다.
    if (forks === 1) await clock.sleep(60_000)
    return answered('["테스트 추가"]')
  })

  await $.turn.complete(TURN)
  await clock.settle()
  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  await clock.settle()

  expect(forks).toBe(2)
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ key: 'step-0' })).toBeDefined()
})

test('/next-steps usage는 마지막 호출과 세션 누적 토큰을 보여 준다', async ($, on) => {
  const clock = mock.clock(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => ({
    value: {
      isAnswered: true as const,
      text: '["x"]',
      usage: { input_tokens: 300, output_tokens: 80, cache_read_input_tokens: 40000, cache_creation_input_tokens: 1200 },
    },
  }))

  expect((await $.command.run(runCommand('usage'))).text).toContain('아직')

  await $.turn.complete(TURN)
  await $.turn.complete({ ...TURN, turnId: 'turn-2' })
  await clock.settle()

  const { text } = await $.command.run(runCommand('usage'))
  expect(text).toContain('마지막 호출: 입력 300 · 캐시 읽기 40,000 · 캐시 쓰기 1,200 · 출력 80')
  expect(text).toContain('이 세션 누적 (2회): 입력 600 · 캐시 읽기 80,000 · 캐시 쓰기 2,400 · 출력 160')
})

function contextOf(tokens: number) {
  return { value: { startedAt: 0, context: { tokens, window: 200_000 }, rateLimits: [] } }
}

test('Haiku에 보낼 대화는 최근 것부터 잘라 길이를 묶는다', async () => {
  const long = 'a'.repeat(5_000)
  const messages = Array.from({ length: 30 }, (_, i) => ({ role: i % 2 ? 'assistant' as const : 'user' as const, text: `<msg-${i}> ${long}` }))
  const transcript = recentTranscript(messages)

  expect(transcript.length).toBeLessThanOrEqual(24_000 + 20)
  expect(transcript).toContain('<msg-29>')
  expect(transcript).not.toContain('<msg-0>')
  expect(recentTranscript([{ role: 'user', text: '  ' }])).toBe('')
})

test('기본은 Haiku에 최근 대화만 보내고 fork는 쓰지 않는다', async ($, on) => {
  const clock = mock.clock(on)
  engineBand(on)
  let forks = 0
  let asked = { model: '', prompt: '' }
  on('turn.complete', () => ({ text: '' }))
  on('session.messages', () => ({ value: [{ role: 'user' as const, text: '회원가입 API 만들어 줘', toolUses: [] }] }))
  on('model.fork', () => {
    forks += 1
    return answered('["x"]')
  })
  on('model.complete', ($, e) => {
    asked = { model: e.model, prompt: e.prompt }
    return answered('["테스트 추가"]')
  })

  await $.turn.complete(TURN)
  await clock.settle()

  expect(forks).toBe(0)
  expect(asked.model).toBe('haiku')
  expect(asked.prompt).toContain('사용자: 회원가입 API 만들어 줘')
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ key: 'step-0' })).toBeDefined()
})

test('fork 모드에서 대화가 기준보다 길면 자동 제안을 건너뛰고, 직접 요청은 만든다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  engineBand(on)
  let forks = 0
  on('turn.complete', () => ({ text: '' }))
  on('session.usage', () => contextOf(150_000))
  answerWith(on, () => {
    forks += 1
    return answered('["테스트 추가"]')
  })

  await $.command.run(runCommand('model fork'))
  await $.turn.complete(TURN)
  await clock.settle()

  expect(forks).toBe(0)
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '150k 토큰' })).toBeDefined()

  await $.command.run(runCommand(''))
  await clock.settle()
  expect(forks).toBe(1)
  expect(await ui.find({ key: 'step-0' })).toBeDefined()
})

test('기준 안이거나 limit 0이면 fork 모드에서도 자동 제안한다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  let forks = 0
  let tokens = 50_000
  on('turn.complete', () => ({ text: '' }))
  on('session.usage', () => contextOf(tokens))
  answerWith(on, () => {
    forks += 1
    return answered('["x"]')
  })

  await $.command.run(runCommand('model fork'))
  await $.turn.complete(TURN)
  await clock.settle()
  expect(forks).toBe(1)

  tokens = 500_000
  await $.command.run(runCommand('limit 0'))
  await $.turn.complete({ ...TURN, turnId: 'turn-2' })
  await clock.settle()
  expect(forks).toBe(2)
})

test('목록 아래에 이번 제안에 쓴 토큰을 보여 준다', async ($, on) => {
  const clock = mock.clock(on)
  on('turn.complete', () => ({ text: '' }))
  answerWith(on, () => ({
    value: {
      isAnswered: true as const,
      text: '["x"]',
      usage: { input_tokens: 2_300, output_tokens: 80, cache_read_input_tokens: 40_000, cache_creation_input_tokens: 0 },
    },
  }))

  await $.turn.complete(TURN)
  await clock.settle()

  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.find({ type: 'Text', text: '토큰 입력 42k (캐시 40k) · 출력 80 · haiku' })).toBeDefined()
  expect(compactUsage({ input_tokens: 900, output_tokens: 1_234, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 }))
    .toBe('입력 900 · 출력 1.2k')
})

test('잘못된 model·limit 값은 사용법을 알려 준다', async ($, on) => {
  mock.store(on)
  for (const args of ['model opus', 'limit -1', 'limit abc', 'limit']) {
    expect((await $.command.run(runCommand(args))).text).toContain('사용법')
  }
})

test('UI 설정은 범위를 벗어난 값을 받지 않는다', async () => {
  expect(applyUi(DEFAULT_UI, 'count', '6')).toEqual({ ...DEFAULT_UI, count: 6 })
  expect(applyUi(DEFAULT_UI, 'bold', 'off')).toEqual({ ...DEFAULT_UI, bold: false })
  expect(applyUi(DEFAULT_UI, 'gap', '0')).toEqual({ ...DEFAULT_UI, gap: 0 })
  for (const [key, value] of [['count', '0'], ['count', '10'], ['count', '2.5'], ['gap', '3'], ['bold', 'yes'], ['tokens', '']]) {
    expect(typeof applyUi(DEFAULT_UI, key ?? '', value ?? '')).toBe('string')
  }
  expect(applyUi(DEFAULT_UI, 'size', '20')).toContain('글자 크기')
  expect(normalizeUi({ count: 99, bold: 'x', gap: 1, tokens: false })).toEqual({ ...DEFAULT_UI, tokens: false })
  expect(normalizeUi(undefined)).toEqual(DEFAULT_UI)
})

test('ui 설정을 바꾸면 항목 수·굵기·간격·토큰 줄에 반영된다', async ($, on) => {
  const clock = mock.clock(on)
  mock.store(on)
  let prompt = ''
  on('turn.complete', () => ({ text: '' }))
  on('session.messages', () => ({ value: [{ role: 'user' as const, text: '회원가입 API 만들어 줘', toolUses: [] }] }))
  on('model.complete', ($, e) => {
    prompt = e.prompt
    return answered('["a", "b", "c", "d", "e", "f", "g"]')
  })

  for (const args of ['ui count 6', 'ui bold off', 'ui gap 0', 'ui tokens off']) {
    expect((await $.command.run(runCommand(args))).text).toContain('다음 스텝 화면')
  }
  expect((await $.command.run(runCommand('ui'))).text).toContain('항목 수 6 · 굵게 off · 간격 0 · 토큰 줄 off')

  await $.turn.complete(TURN)
  await clock.settle()

  expect(prompt).toContain('최대 6개')
  const ui = await $.ui.mount({ ...BAND, surface: 'terminal' })
  expect(await ui.findAll({ type: 'Button', text: /^\[ \]/ })).toHaveLength(6)
  expect(await ui.find({ type: 'Text', text: '토큰' })).toBeUndefined()
  const drawn = JSON.stringify(await ui.drawn())
  expect(drawn).toContain('"gap":0')
  expect(drawn).not.toContain('"bold":true')
})
