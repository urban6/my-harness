export const MAX_STEPS = 4

export const ASK = `지금까지의 대화를 바탕으로, 사용자가 바로 이어서 요청할 만한 다음 작업을 최대 ${MAX_STEPS}개 제안해.
- 각 항목은 사용자가 그대로 AI에게 보낼 수 있는 한 문장짜리 요청문(명령형)으로, 60자 안팎.
- 대화에서 쓰인 언어로 쓴다.
- 서로 겹치지 않게, 가치가 큰 순서로.
- 이어서 할 일이 없으면 빈 배열.
JSON 문자열 배열 하나만 출력하고 다른 말은 하지 마.`

// 모델 답에서 첫 JSON 배열을 꺼낸다. 코드 펜스나 앞뒤 군말이 붙어도 견딘다.
export function parseSteps(text: string): string[] {
  const found = text.match(/\[[\s\S]*\]/)
  if (!found) return []
  try {
    const value: unknown = JSON.parse(found[0])
    if (!Array.isArray(value)) return []
    return value
      .filter((step): step is string => typeof step === 'string')
      .map(step => step.trim())
      .filter(step => step.length > 0)
      .slice(0, MAX_STEPS)
  } catch {
    return []
  }
}

export function composePrompt(steps: string[]): string {
  if (steps.length === 1) return steps[0] ?? ''
  return ['다음을 순서대로 진행해 줘:', ...steps.map((step, i) => `${i + 1}. ${step}`)].join('\n')
}

export function toggle(picked: number[], index: number): number[] {
  return picked.includes(index)
    ? picked.filter(i => i !== index)
    : [...picked, index].sort((a, b) => a - b)
}
