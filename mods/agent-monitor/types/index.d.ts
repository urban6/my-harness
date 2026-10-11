export type AgentStatus = 'running' | 'completed' | 'failed' | 'killed'

export type AgentRecord = {
  id: string
  type: string
  description: string
  status: AgentStatus
  startedAt: number
  endedAt?: number
  toolCalls: number
  tokens: number
  parentId?: string
  // 워크플로 에이전트: $.agent.list()에 나오지 않으므로 목록 보정에서 '사라짐'으로 보지 않는다.
  isUnlisted?: true
}

declare module 'claude-code' {
  interface PluginState {
    'agent-monitor': {
      records: AgentRecord[]
      now: number
      keep: number
      auto: boolean
      autoOpened: boolean
    }
  }
}
