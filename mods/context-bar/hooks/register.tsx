import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Snapshot, Warn } from '../types'
import type { Cell } from './bar'
import {
  barWidth,
  capacityLabel,
  DEFAULT_WARN,
  describe,
  extras,
  formatTokens,
  HELP,
  layout,
  normalizeWarn,
  parseWarn,
  percentColor,
  toSnapshot,
  warnPercent,
} from './bar'

const enabled = atom({ plugin: 'context-bar', key: 'enabled' } as const, true)
const warn = atom({ plugin: 'context-bar', key: 'warn' } as const, DEFAULT_WARN as Warn)
// 마지막 측정값. 측정 전·압축 직후·꺼짐이면 null.
const snapshot = atom({ plugin: 'context-bar', key: 'snapshot' } as const, null as Snapshot | null)

const STORE_ENABLED = 'enabled'
const STORE_WARN = 'warn'

const GLYPH = { used: '█', free: '░', buffer: '·' } as const

function cellStyle(cell: Cell) {
  if (cell.kind === 'used') return { color: cell.color }
  if (cell.kind === 'free') return { color: 'inactive' }
  return { dimColor: true }
}

// summary는 로컬 추정이라 호출 비용이 없다.
async function measure($: EngineInterface) {
  try {
    const { context } = await $.session.usage({ breakdown: 'summary' })
    const breakdown = context.breakdown
    if (breakdown) await update($, snapshot, prev => toSnapshot(breakdown, prev))
  } catch (error) {
    $.ui.log(`context-bar: 컨텍스트 사용량을 읽지 못했어요 (${String(error)})`)
  }
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const storedEnabled = await $.store.get(STORE_ENABLED)
    await update($, enabled, () => storedEnabled !== false)
    const storedWarn = normalizeWarn(await $.store.get(STORE_WARN))
    await update($, warn, () => storedWarn)
    await update($, snapshot, () => null)
    await $.command.register({
      name: 'context-bar',
      description: '컨텍스트 막대: on/off, warn <노랑> <빨강>, 인자 없으면 현재 설정',
      argumentHint: '[on|off|warn]',
    })
    return next(e)
  })

  // /clear·resume에는 session.start가 오지 않는다. 대화가 바뀌었으니 이전 측정값을 버린다.
  on('session.end', async ($, e, next) => {
    if (e.reason === 'clear' || e.reason === 'resume') await update($, snapshot, () => null)
    return next(e)
  })

  // 메인 턴이 끝날 때마다 온다. 서브에이전트 턴에는 오지 않는다.
  on('session.measure', async ($, e, next) => {
    const done = await next(e)
    if (e.changed.includes('context') && (await read($, enabled))) await measure($)
    return done
  })

  // 압축된 대화는 다음 응답이 올 때까지 측정값이 없다.
  on('session.compact', async ($, e, next) => {
    const result = await next(e)
    const isApplied = e.agentId === undefined && e.trigger !== 'precompute' && !('skip' in result)
    if (isApplied) await update($, snapshot, () => null)
    return result
  })

  on('command.run', { command: 'context-bar' }, async ($, e) => {
    const [sub = '', ...rest] = e.args.trim().toLowerCase().split(/\s+/)

    if (sub === '') {
      return { text: describe(await read($, enabled), await read($, warn), await read($, snapshot)) }
    }
    if ((sub === 'on' || sub === 'off') && rest.length === 0) {
      const isOn = sub === 'on'
      await update($, enabled, () => isOn)
      await $.store.set(STORE_ENABLED, isOn)
      // 꺼진 동안 측정하지 않으므로, 다시 켰을 때 오래된 값을 보여주지 않는다.
      if (!isOn) await update($, snapshot, () => null)
      return { text: isOn ? '컨텍스트 막대를 켰어요. 다음 턴부터 다시 측정해요.' : '컨텍스트 막대를 껐어요.' }
    }
    if (sub === 'warn') {
      const parsed = parseWarn(rest.join(' '))
      if (typeof parsed === 'string') return { text: parsed }
      await update($, warn, () => parsed)
      await $.store.set(STORE_WARN, parsed)
      return { text: `경고 기준: ${parsed.yellow}% 노랑 · ${parsed.red}% 빨강` }
    }
    return { text: HELP }
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const above = await next(e)
    if (e.props.hasSurvey || !(await read($, enabled))) return above

    const { Box, Text } = $.ui.resolve(e)
    const snap = await read($, snapshot)
    if (snap === null) {
      return (
        <Box flexDirection="column">
          {above}
          <Text key="context-bar" dimColor>컨텍스트 측정 전</Text>
        </Box>
      )
    }

    const width = barWidth(e.props.bodyColumns)
    const cells = layout(snap.segments, width)
    const color = percentColor(warnPercent(snap), await read($, warn))
    const { top, delta } = extras(snap, e.props.bodyColumns, width)
    return (
      <Box flexDirection="column">
        {above}
        <Box key="context-bar" flexDirection="row">
          {cells.map((cell, i) => (
            <Text key={`cell-${i}`} {...cellStyle(cell)}>
              {GLYPH[cell.kind].repeat(cell.cells)}
            </Text>
          ))}
          <Text>  </Text>
          <Text key="percent" {...(color ? { color } : {})}>
            {`${snap.percent}%`}
          </Text>
          <Text dimColor>{` · ${capacityLabel(snap)}`}</Text>
          {top && <Text dimColor> · </Text>}
          {top && <Text key="top" color={top.color}>{top.name}</Text>}
          {top && <Text dimColor>{` ${formatTokens(top.tokens)}`}</Text>}
          {delta && <Text dimColor>{` · ${delta}`}</Text>}
        </Box>
      </Box>
    )
  })
}
