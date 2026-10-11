// 막대 한 구간. breakdown의 categories에서 deferred(창 밖)를 뺀 것.
export type Segment = {
  kind: 'used' | 'free' | 'buffer'
  // /context가 쓰는 테마 색 키 (promptBorder, permission, inactive, ...)
  color: string
  tokens: number
}

// 마지막 측정값. 그리는 데 필요한 값만 남긴다.
export type Snapshot = {
  segments: Segment[]
  totalTokens: number
  // breakdown.rawMaxTokens
  maxTokens: number
  // breakdown.percentage (rawMaxTokens 대비, 넘치면 100 초과)
  percent: number
}

// 비율 숫자 색 기준(%)
export type Warn = { yellow: number; red: number }

declare module 'claude-code' {
  interface PluginState {
    'context-bar': {
      enabled: boolean
      warn: Warn
      snapshot: Snapshot | null
    }
  }
}
