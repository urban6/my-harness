import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Phase } from '../types'
import { ASK, composePrompt, parseSteps, toggle } from './steps'

const phase = atom({ plugin: 'next-steps', key: 'phase' } as const, 'idle' as Phase)
const steps = atom({ plugin: 'next-steps', key: 'steps' } as const, [] as string[])
const picked = atom({ plugin: 'next-steps', key: 'picked' } as const, [] as number[])
// 제안을 요청한 턴. 새 프롬프트가 들어오면 비워서 늦게 도착한 제안을 버린다.
const turnId = atom({ plugin: 'next-steps', key: 'turnId' } as const, '')
const isOn = atom({ plugin: 'next-steps', key: 'isOn' } as const, true)

const STORE_IS_ON = 'isOn'

async function clear($: EngineInterface) {
  await update($, turnId, () => '')
  await update($, phase, () => 'idle' as Phase)
  await update($, steps, () => [])
  await update($, picked, () => [])
}

async function suggest($: EngineInterface, id: string) {
  await update($, turnId, () => id)
  await update($, steps, () => [])
  await update($, picked, () => [])
  await update($, phase, () => 'loading' as Phase)

  const reply = await $.model.fork({ prompt: ASK })
  if ((await read($, turnId)) !== id) return

  const found = reply.isAnswered ? parseSteps(reply.text) : []
  await update($, steps, () => found)
  await update($, phase, () => (found.length > 0 ? 'ready' : 'idle') as Phase)
}

async function fill($: EngineInterface) {
  const list = await read($, steps)
  const chosen = (await read($, picked)).map(i => list[i]).filter((s): s is string => !!s)
  if (chosen.length === 0) {
    $.ui.toast('먼저 번호로 항목을 골라 주세요')
    return
  }

  const filled = await $.prompt.fill({ text: composePrompt(chosen), mode: 'insert' })
  if (filled.isFilled) await clear($)
  else $.ui.toast('입력창에 넣지 못했어요')
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const stored = await $.store.get(STORE_IS_ON)
    await update($, isOn, () => stored !== false)
    await $.command.register({
      name: 'next-steps',
      description: '다음 스텝 제안: 인자 없으면 지금 제안, on/off로 자동 제안 켜고 끄기',
      argumentHint: '[on|off]',
    })

    return next(e)
  })

  on('command.run', { command: 'next-steps' }, async ($, e) => {
    const arg = e.args.trim().toLowerCase()

    if (arg === 'on' || arg === 'off') {
      const value = arg === 'on'
      await update($, isOn, () => value)
      await $.store.set(STORE_IS_ON, value)
      if (!value) await clear($)
      return { text: `다음 스텝 자동 제안을 ${value ? '켰어요' : '껐어요'}.` }
    }
    if (arg !== '') return { text: '사용법: /next-steps [on|off]' }

    void suggest($, `manual-${await $.clock.now()}`)
    return { text: '다음 스텝을 정리하고 있어요.' }
  })

  // 새 프롬프트가 나가면 이전 제안은 의미가 없다.
  on('prompt.submit', async ($, e, next) => {
    await clear($)
    return next(e)
  })

  on('turn.complete', async ($, e, next) => {
    const done = await next(e)
    const isMainAnswer = e.agentId === undefined && e.reason === 'answer'
    if (isMainAnswer && (await read($, isOn))) {
      // 턴 종료를 붙잡지 않도록 fork는 기다리지 않는다.
      void suggest($, e.turnId)
    }

    return done
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    if (e.props.hasSurvey || e.props.isWorking) return next(e)

    const now = await read($, phase)
    if (now === 'idle') return next(e)

    const { Box, Button, Text } = $.ui.resolve(e)
    if (now === 'loading') return <Text dimColor>다음 스텝 정리 중…</Text>

    const list = await read($, steps)
    const chosen = await read($, picked)

    return (
      <Box flexDirection="column" gap={1}>
        <Text dimColor>다음 스텝 — 숫자로 고르고 0으로 입력창에 채우기</Text>
        {list.map((step, i) => (
          <Button
            key={`step-${i}`}
            plain
            hotkey={String(i + 1)}
            onPress={() => update($, picked, list => toggle(list, i))}
          >
            <Text bold>
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
      </Box>
    )
  })
}
