export type Phase = 'idle' | 'loading' | 'ready'

declare module 'claude-code' {
  interface PluginState {
    'next-steps': {
      phase: Phase
      steps: string[]
      picked: number[]
      turnId: string
      isOn: boolean
    }
  }
}
