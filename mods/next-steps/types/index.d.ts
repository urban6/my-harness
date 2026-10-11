export type Phase = 'idle' | 'loading' | 'ready'

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
    }
  }
}
