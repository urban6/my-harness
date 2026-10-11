import { atom, read, update } from 'claude-code'
import type { EngineInterface, ModelForkResult, Register } from 'claude-code'

import type { Mode, Phase, Usage } from '../types'
import {
  addUsage,
  ASK,
  compactUsage,
  composePrompt,
  DEFAULT_LIMIT,
  formatTokens,
  formatUsage,
  NO_USAGE,
  parseSteps,
  recentTranscript,
  toggle,
} from './steps'

const phase = atom({ plugin: 'next-steps', key: 'phase' } as const, 'idle' as Phase)
const steps = atom({ plugin: 'next-steps', key: 'steps' } as const, [] as string[])
const picked = atom({ plugin: 'next-steps', key: 'picked' } as const, [] as number[])
// 제안을 요청한 턴. 새 프롬프트가 들어오면 비워서 늦게 도착한 제안을 버린다.
const turnId = atom({ plugin: 'next-steps', key: 'turnId' } as const, '')
const isOn = atom({ plugin: 'next-steps', key: 'isOn' } as const, true)
const mode = atom({ plugin: 'next-steps', key: 'mode' } as const, 'haiku' as Mode)
const limit = atom({ plugin: 'next-steps', key: 'limit' } as const, DEFAULT_LIMIT)
// 자동 제안을 건너뛰었을 때의 컨텍스트 크기. 밴드 안내에 쓴다.
const contextTokens = atom({ plugin: 'next-steps', key: 'contextTokens' } as const, 0)
// 제안 생성 호출의 토큰 사용량. 이 세션 동안만 모은다.
const lastUsage = atom({ plugin: 'next-steps', key: 'lastUsage' } as const, null as Usage | null)
const totalUsage = atom({ plugin: 'next-steps', key: 'totalUsage' } as const, NO_USAGE)
const calls = atom({ plugin: 'next-steps', key: 'calls' } as const, 0)

const STORE_IS_ON = 'isOn'
const STORE_MODE = 'mode'
const STORE_LIMIT = 'limit'
const HELP = '사용법: /next-steps [on|off|usage|model haiku|fork|limit <토큰, 0은 끔>]'

async function clear($: EngineInterface) {
  await update($, turnId, () => '')
  await update($, phase, () => 'idle' as Phase)
  await update($, steps, () => [])
  await update($, picked, () => [])
}

async function ask($: EngineInterface): Promise<ModelForkResult> {
  if ((await read($, mode)) === 'fork') return $.model.fork({ prompt: ASK })

  const transcript = recentTranscript(await $.session.messages())
  if (transcript === '') return { isAnswered: false, reason: 'nothing-to-fork' }
  return $.model.complete({
    model: 'haiku',
    prompt: `<대화>\n${transcript}\n</대화>\n\n${ASK}`,
    maxTokens: 500,
  })
}

async function suggest($: EngineInterface, id: string) {
  await update($, turnId, () => id)
  await update($, steps, () => [])
  await update($, picked, () => [])
  await update($, phase, () => 'loading' as Phase)

  const reply = await ask($)
  // 버려진 호출도 비용은 들었으니 누적에는 넣는다.
  if ('usage' in reply) {
    const usage = reply.usage
    await update($, totalUsage, total => addUsage(total, usage))
    await update($, calls, n => n + 1)
  }
  if ((await read($, turnId)) !== id) return

  // 밴드에 보이는 토큰은 지금 보이는 목록을 만든 호출의 것이어야 한다.
  if ('usage' in reply) {
    const usage = reply.usage
    await update($, lastUsage, () => usage)
  }
  const found = reply.isAnswered ? parseSteps(reply.text) : []
  await update($, steps, () => found)
  await update($, phase, () => (found.length > 0 ? 'ready' : 'idle') as Phase)
}

// fork는 대화 전체를 보내므로, 대화가 기준보다 길면 자동 제안은 건너뛴다.
async function isTooLong($: EngineInterface): Promise<boolean> {
  const max = await read($, limit)
  if ((await read($, mode)) !== 'fork' || max <= 0) return false

  const tokens = (await $.session.usage()).context.tokens ?? 0
  if (tokens <= max) return false

  await clear($)
  await update($, contextTokens, () => tokens)
  await update($, phase, () => 'skipped' as Phase)
  return true
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
    const storedIsOn = await $.store.get(STORE_IS_ON)
    const storedMode = await $.store.get(STORE_MODE)
    const storedLimit = await $.store.get(STORE_LIMIT)
    await update($, isOn, () => storedIsOn !== false)
    await update($, mode, () => (storedMode === 'fork' ? 'fork' : 'haiku') as Mode)
    await update($, limit, () => (typeof storedLimit === 'number' ? storedLimit : DEFAULT_LIMIT))
    await $.command.register({
      name: 'next-steps',
      description: '다음 스텝 제안: 인자 없으면 지금 제안, on/off, usage, model haiku|fork, limit <토큰>',
      argumentHint: '[on|off|usage|model|limit]',
    })

    // 모드가 다시 로드되면 진행 중이던 호출은 끊기지만 $.state는 남는다.
    // "정리 중"인 채로 남은 턴이 있으면 이어서 다시 요청한다.
    const pending = await read($, turnId)
    if (pending !== '' && (await read($, phase)) === 'loading') {
      void suggest($, pending)
    }

    return next(e)
  })

  on('command.run', { command: 'next-steps' }, async ($, e) => {
    const [sub = '', value = ''] = e.args.trim().toLowerCase().split(/\s+/)

    if (sub === 'on' || sub === 'off') {
      const isOnNow = sub === 'on'
      await update($, isOn, () => isOnNow)
      await $.store.set(STORE_IS_ON, isOnNow)
      if (!isOnNow) await clear($)
      return { text: `다음 스텝 자동 제안을 ${isOnNow ? '켰어요' : '껐어요'}.` }
    }
    if (sub === 'model') {
      if (value !== 'haiku' && value !== 'fork') return { text: HELP }
      await update($, mode, () => value as Mode)
      await $.store.set(STORE_MODE, value)
      return {
        text: value === 'haiku'
          ? '다음 스텝을 Haiku로 만들어요. 최근 대화만 보내서 비용이 낮고 일정해요.'
          : '다음 스텝을 세션 모델로 만들어요. 대화 전체를 보고 만들어서 정확하지만, 대화가 길수록 비용이 늘어요.',
      }
    }
    if (sub === 'limit') {
      const max = Number(value)
      if (value === '' || !Number.isInteger(max) || max < 0) return { text: HELP }
      await update($, limit, () => max)
      await $.store.set(STORE_LIMIT, max)
      return {
        text: max === 0
          ? '대화 길이와 상관없이 자동 제안해요.'
          : `fork 모드에서 컨텍스트가 ${formatTokens(max)} 토큰을 넘으면 자동 제안을 건너뛰어요.`,
      }
    }
    if (sub === 'usage') {
      const max = await read($, limit)
      const settings = `설정: 모델 ${await read($, mode)} · 자동 제안 기준 ${max === 0 ? '없음' : `${formatTokens(max)} 토큰 (fork일 때)`}`
      const last = await read($, lastUsage)
      if (last === null) return { text: `${settings}\n이 세션에서는 아직 다음 스텝을 만든 적이 없어요.` }
      return {
        text: [
          settings,
          `마지막 호출: ${formatUsage(last)}`,
          `이 세션 누적 (${await read($, calls)}회): ${formatUsage(await read($, totalUsage))}`,
        ].join('\n'),
      }
    }
    if (sub !== '') return { text: HELP }

    // 직접 요청은 길이 기준과 상관없이 만든다.
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
    if (isMainAnswer && (await read($, isOn)) && !(await isTooLong($))) {
      // 턴 종료를 붙잡지 않도록 제안 생성은 기다리지 않는다.
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
    if (now === 'skipped') {
      const tokens = formatTokens(await read($, contextTokens))
      return (
        <Text dimColor>
          대화가 길어(약 {tokens} 토큰) 자동 제안을 건너뛰었어요 · /next-steps로 직접 요청
        </Text>
      )
    }

    const list = await read($, steps)
    const chosen = await read($, picked)
    const usage = await read($, lastUsage)
    const current = await read($, mode)

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
        {usage !== null && (
          <Text dimColor>
            토큰 {compactUsage(usage)} · {current}
          </Text>
        )}
      </Box>
    )
  })
}
