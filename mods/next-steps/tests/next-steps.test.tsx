import { expect, mock, test } from 'claude-code/testing'
import type { On, TurnCompleteInput } from 'claude-code'

import { composePrompt, parseSteps } from '../hooks/steps'

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
  on('model.fork', () => answered('["테스트 추가", "에러 응답 정리", "문서화"]'))
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
  on('model.fork', () => {
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
  on('model.fork', async () => {
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
