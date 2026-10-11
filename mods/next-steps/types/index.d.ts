export type Phase = 'idle' | 'loading' | 'ready' | 'skipped'

// haiku: 최근 대화만 잘라 Haiku에 보낸다(싸고 비용이 일정). fork: 대화 전체를 세션 모델로(정확하지만 길수록 비쌈).
export type Mode = 'haiku' | 'fork'

export type Usage = {
  input_tokens: number
  output_tokens: number
  cache_read_input_tokens: number
  cache_creation_input_tokens: number
}

declare module 'claude-code' {
  interface PluginState {
    'next-steps': {
      phase: Phase
      steps: string[]
      picked: number[]
      turnId: string
      isOn: boolean
      lastUsage: Usage | null
      totalUsage: Usage
      calls: number
      mode: Mode
      limit: number
      contextTokens: number
    }
  }
}
