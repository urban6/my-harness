import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register, Timer } from 'claude-code'

import type { AgentRecord, AgentStatus } from '../types'
import {
  addTokens,
  addToolCall,
  columnsFor,
  counts,
  DEFAULT_KEEP,
  descriptionWidth,
  finish,
  header,
  HELP,
  isTracked,
  normalizeKeep,
  order,
  parseKeep,
  reconcile,
  rowText,
  startRecord,
  trim,
  typeWidth,
  visible,
} from './agents'

const PANE = 'agent-monitor'
const TITLE = '서브에이전트'

const records = atom({ plugin: 'agent-monitor', key: 'records' } as const, [] as AgentRecord[])
// 틱이 갱신하는 현재 시각. 렌더는 이 값으로 경과 시간을 계산한다.
const now = atom({ plugin: 'agent-monitor', key: 'now' } as const, 0)
const keep = atom({ plugin: 'agent-monitor', key: 'keep' } as const, DEFAULT_KEEP)
const auto = atom({ plugin: 'agent-monitor', key: 'auto' } as const, true)
const autoOpened = atom({ plugin: 'agent-monitor', key: 'autoOpened' } as const, false)

const STORE_KEEP = 'keep'
const STORE_AUTO = 'auto'

const SYMBOL: Record<AgentStatus, string> = { running: '●', completed: '✓', failed: '✗', killed: '■' }

function symbolStyle(status: AgentStatus) {
  if (status === 'running') return { color: 'suggestion' }
  if (status === 'completed') return { color: 'success' }
  if (status === 'failed') return { color: 'error' }
  return { dimColor: true }
}

// 모듈 변수라 다시 로드되면 사라진다. session.start에서 필요하면 다시 시작한다.
let tick: Timer | undefined

async function guard($: EngineInterface, work: () => Promise<void>) {
  try {
    await work()
  } catch (error) {
    $.ui.log(`agent-monitor: ${String(error)}`)
  }
}

const hasRunning = (list: AgentRecord[]) => list.some(r => r.status === 'running')

function stopTick() {
  tick?.cancel()
  tick = undefined
}

// 엔진 목록으로 놓친 종료를 맞추고 현재 시각을 올린다. 목록을 못 읽어도 시간은 흐른다.
async function refresh($: EngineInterface) {
  const at = await $.clock.now()
  const listed = await $.agent.list().catch(() => undefined)
  if (listed) {
    const limit = await read($, keep)
    await update($, records, list => trim(reconcile(list, listed, at), limit))
  }
  await update($, now, () => at)
  if (!hasRunning(await read($, records))) {
    stopTick()
    // 위 읽기와 멈춤 사이에 뜬 에이전트는 틱이 돌고 있다고 보고 새로 시작하지 않았다.
    if (hasRunning(await read($, records))) startTick($)
  }
}

function startTick($: EngineInterface) {
  if (tick) return
  tick = $.clock.every(1_000, () => {
    void guard($, () => refresh($))
  })
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const storedKeep = normalizeKeep(await $.store.get(STORE_KEEP))
    await update($, keep, () => storedKeep)
    const storedAuto = await $.store.get(STORE_AUTO)
    await update($, auto, () => storedAuto !== false)
    await $.command.register({
      name: 'agent-monitor',
      description: '서브에이전트 패널: 인자 없으면 열기, keep <N>, clear, auto on|off',
      argumentHint: '[keep|clear|auto]',
    })
    if (hasRunning(await read($, records))) startTick($)
    return next(e)
  })

  // /clear·resume에는 session.start가 오지 않는다. 대화가 바뀌었으니 기록을 버린다.
  on('session.end', async ($, e, next) => {
    if (e.reason === 'clear' || e.reason === 'resume') {
      stopTick()
      await update($, records, () => [])
      await update($, autoOpened, () => false)
    }
    return next(e)
  })

  on('agent.spawn', async ($, e, next) => {
    const result = await next(e)
    if (result.deny !== undefined || result.agentId === undefined) return result
    const agentId = result.agentId
    await guard($, async () => {
      const at = await $.clock.now()
      await update($, records, list => [...list, startRecord(e, agentId, at)])
      await update($, now, () => at)
      startTick($)
      if ((await read($, auto)) && !(await read($, autoOpened))) {
        await update($, autoOpened, () => true)
        await $.ui.open({ id: PANE, title: TITLE })
      }
    })
    return result
  })

  on('tool.call', async ($, e, next) => {
    const agentId = e.agentId
    if (agentId !== undefined && isTracked(await read($, records), agentId)) {
      await guard($, () => update($, records, list => addToolCall(list, agentId)))
    }
    return next(e)
  })

  on('turn.step', async function* ($, e, next) {
    const result = yield* next(e)
    const agentId = e.agentId
    const usage = result.usage
    if (agentId !== undefined && usage && isTracked(await read($, records), agentId)) {
      await guard($, () => update($, records, list => addTokens(list, agentId, usage)))
    }
    return result
  })

  on('turn.complete', async ($, e, next) => {
    const result = await next(e)
    const agentId = e.agentId
    if (agentId !== undefined && isTracked(await read($, records), agentId)) {
      await guard($, async () => {
        const at = await $.clock.now()
        const limit = await read($, keep)
        await update($, records, list => trim(finish(list, agentId, e.reason, at), limit))
        await update($, now, () => at)
      })
    }
    return result
  })

  on('command.run', { command: 'agent-monitor' }, async ($, e) => {
    const [sub = '', ...rest] = e.args.trim().toLowerCase().split(/\s+/)

    if (sub === '' && rest.length === 0) {
      await $.ui.open({ id: PANE, title: TITLE })
      const { running, ended } = counts(await read($, records))
      return { text: `서브에이전트 패널을 열었어요 (실행 중 ${running} · 완료 ${ended})` }
    }
    if (sub === 'keep') {
      const parsed = parseKeep(rest.join(' '))
      if (typeof parsed === 'string') return { text: parsed }
      await update($, keep, () => parsed)
      await $.store.set(STORE_KEEP, parsed)
      await update($, records, list => trim(list, parsed))
      return { text: `끝난 에이전트를 최근 ${parsed}개까지 남겨요.` }
    }
    if (sub === 'clear' && rest.length === 0) {
      await update($, records, list => list.filter(r => r.status === 'running'))
      return { text: '끝난 기록을 지웠어요.' }
    }
    if (sub === 'auto' && rest.length === 1 && (rest[0] === 'on' || rest[0] === 'off')) {
      const isOn = rest[0] === 'on'
      await update($, auto, () => isOn)
      await $.store.set(STORE_AUTO, isOn)
      return { text: isOn ? '패널 자동 열기를 켰어요.' : '패널 자동 열기를 껐어요.' }
    }
    return { text: HELP }
  })

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Text } = $.ui.resolve(e)
    const list = order(await read($, records))
    if (list.length === 0) return <Text dimColor>아직 실행된 서브에이전트가 없어요</Text>

    const at = await read($, now)
    const cols = columnsFor(e.props.bodyColumns, typeWidth(list), descriptionWidth(list))
    // 머리줄과 빈 줄 두 행을 뺀 만큼 그린다.
    const { shown, hidden } = visible(list, e.props.scroll.bodyRows - 2)
    return (
      <Box flexDirection="column">
        <Text key="header" bold>{header(list)}</Text>
        <Text> </Text>
        {shown.map(r => (
          <Box key={`agent-${r.id}`} flexDirection="row">
            <Text {...symbolStyle(r.status)}>{SYMBOL[r.status]}</Text>
            <Text dimColor={r.status === 'killed'}>{` ${rowText(r, at, cols)}`}</Text>
          </Box>
        ))}
        {hidden > 0 && <Text dimColor>{`… 외 ${hidden}개`}</Text>}
      </Box>
    )
  })
}
