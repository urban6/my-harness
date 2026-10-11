# agent-monitor 설계

이 세션에서 띄운 서브에이전트의 상태·경과 시간·도구 호출 수·토큰을 사이드 패널에 실시간으로 보여주는 모드.

## 목적과 범위

- **목적**: 서브에이전트를 여럿 띄우거나 백그라운드로 돌릴 때, 누가 무엇을 하고 있는지와 멈췄는지를 Claude에게 묻지 않고 확인한다.
- **성공 기준**
  - 서브에이전트가 뜨면 패널에 자동으로 나타난다(첫 에이전트에서 패널이 열린다).
  - 실행 중인 에이전트의 경과 시간이 1초마다 갱신된다.
  - 도구 호출 수와 토큰이 에이전트별로 누적된다.
  - 끝나면 완료·실패·중단 상태와 총 소요 시간이 남는다.
  - 특정 에이전트 이름·프로젝트에 의존하지 않는다.
- **범위 밖**: 현재 도구 호출 표시, 부모-자식 트리, 에이전트 상세 보기·클릭 상호작용, 에이전트 중단 버튼, 세션 간 기록 보관, 팀메이트 전용 표시(목록에 나오면 일반 에이전트처럼 다룬다).

## 화면

`$.ui.open({ id: 'agent-monitor', title: '서브에이전트' })`로 연 패널에 그린다.

```
서브에이전트 · 실행 중 2 · 완료 3

● backend-impl       주문 서비스 구현      2m 14s   18회   42.1k
● boundary-verifier  경계면 검증             48s    7회   11.3k
✓ backend-designer   주문 API·DB 설계     3m 02s   24회   58.7k
✗ Explore            결제 모듈 탐색          21s    5회    6.2k
■ general-purpose    로그 분석             1m 05s    9회   15.0k
```

- **머리줄**: `서브에이전트 · 실행 중 N · 완료 M`. `완료`는 끝난(완료·실패·중단) 기록 수다. 0인 항목은 뺀다.
- **행**: 상태 기호 → 타입 → 작업 설명 → 시간 → 도구 호출 수 → 토큰.
  - 기호와 색: `●` 실행 중(`suggestion`), `✓` 완료(`success`), `✗` 실패(`error`), `■` 중단(`dimColor`). 실행 대기(`pending`)도 `●`로 그린다.
  - 시간: 실행 중이면 `now - startedAt`, 끝났으면 `endedAt - startedAt`.
  - 타입 열은 표시 중인 행의 가장 긴 타입에 맞춰 정렬하되 최대 20칸, 넘으면 말줄임.
- **좁은 화면**: 작업 설명을 먼저 말줄임으로 줄인다(최소 8칸). 그래도 모자라면 토큰 열, 그다음 호출 수 열을 뺀다.
- **정렬**: 실행 중은 `startedAt` 오름차순으로 위에, 끝난 것은 `endedAt` 내림차순으로 아래에.
- **높이**: `viewport.rows - 2`(머리줄·빈 줄)를 넘으면 실행 중을 먼저 채우고 끝난 것을 자른다. 잘린 수는 마지막 줄에 `… 외 N개`로 흐리게 표시한다.
- **빈 상태**: `아직 실행된 서브에이전트가 없어요`를 흐리게 표시한다.

## 데이터

에이전트 기록 한 건:

```ts
export type AgentStatus = 'running' | 'completed' | 'failed' | 'killed'

export type AgentRecord = {
  id: string            // agentId
  type: string          // subagentType
  description: string
  status: AgentStatus
  startedAt: number     // ms
  endedAt?: number
  toolCalls: number
  tokens: number
  parentId?: string     // 범위 밖(트리)이지만 나중을 위해 남긴다
  isUnlisted?: true     // 워크플로 에이전트(spawn 입력에 workflow가 있음). $.agent.list()에 나오지 않는다
}
```

- `tokens`는 그 에이전트의 모든 API 요청 `usage`에서 `input_tokens + output_tokens + cache_creation_input_tokens + cache_read_input_tokens`의 합이다. 처리한 총량이며 비용 환산값이 아니다.
- 엔진의 `pending`·`running`·`waiting`·`idle`은 모두 `running`으로, `completed`는 `completed`, `failed`는 `failed`, `killed`는 `killed`로 대응한다.

## 동작

| 이벤트 | 처리 |
|---|---|
| `session.start` | `$.store`에서 `keep`·`auto`를 읽어 상태에 반영. `/agent-monitor` 명령 등록. `running` 기록이 남아 있으면(모드 다시 로드) 틱을 다시 시작 |
| `session.end` | `reason`이 `clear`·`resume`이면 기록과 `autoOpened`를 비우고 틱을 멈춘다 |
| `agent.spawn` | `await next(e)`. 결과에 `agentId`가 있으면 `running` 기록을 추가(`type = subagentType`, `description`, `parentId = parentAgentId`, `startedAt = now`). `deny`이거나 `agentId`가 없으면 기록하지 않는다. `auto`가 켜져 있고 이 세션에서 아직 자동으로 연 적이 없으면 패널을 연다. 틱 시작 |
| `tool.call` | `e.agentId`가 추적 중인 기록이면 `toolCalls + 1`. 없으면(메인 루프·모르는 루프) 무시. 결과는 `next(e)` 그대로 |
| `turn.step` | `await next(e)` 후 입력의 `agentId`가 추적 중이면 결과 `usage`를 `tokens`에 더한다 |
| `turn.complete` | `e.agentId`가 추적 중이고 `running`이면 `reason`으로 상태를 정한다: `answer` → `completed`, `aborted` → `killed`, `error`·`refusal` → `failed`. `endedAt = now` |
| 틱(1초) | `now`를 갱신해 패널을 다시 그린다. `$.agent.list()`로 상태를 맞춘다(아래). `running` 기록이 없으면 틱을 멈춘다 |
| `ui.render` `Pane` `agent-monitor` | 상태만 읽어 그린다 |
| `command.run` `agent-monitor` | 아래 명령 처리 |

**보정(틱마다)**
- 목록에 있고 끝난 상태(`completed`·`failed`·`killed`)인데 기록이 `running`이면 그 상태로 바꾸고 `endedAt = now`.
- 기록이 `running`인데 목록에 없으면 엔진이 정리한 것이다. `completed`로 바꾸고 `endedAt = now`. 단, 다음 기록은 제외한다.
  - 목록을 요청하기 직전 시각(`now`) 이후에 시작한 기록: 목록에 아직 없을 수 있다(목록을 기다리는 사이 뜬 에이전트).
  - 워크플로 에이전트(`isUnlisted`): 목록에 나오지 않으므로 `turn.complete`로만 끝을 정한다.
- `turn.complete`가 먼저 정한 상태는 덮어쓰지 않는다(이벤트가 더 정확하다).
- `$.agent.list()` 호출이 실패하면 이번 틱의 보정만 건너뛴다.

**틱**
- 실행 중 기록이 없어 틱을 멈춘 직후 기록을 다시 읽어, 그사이 뜬 에이전트가 있으면 다시 시작한다.
- `$.clock.every(1000, fn)`의 취소 함수를 모듈 변수에 둔다. 이미 돌고 있으면 새로 시작하지 않는다.
- 다시 로드되면 모듈 변수와 타이머가 사라지므로 `session.start`에서 필요하면 다시 시작한다.

**보관**
- 끝난 기록은 `endedAt` 내림차순으로 `keep`개(기본 10)만 남긴다. 기록이 끝날 때와 `keep`을 바꿀 때 자른다.
- 실행 중 기록은 개수 제한이 없다.

**오류 처리**
- 모든 훅은 `next(e)`의 결과를 그대로 돌려준다. 모드 실패가 에이전트 실행이나 도구 결과를 바꾸지 않는다.
- 상태 갱신 중 예외는 잡아서 `$.ui.log('agent-monitor: …')`에 남긴다.

## 명령

- `/agent-monitor`(인자 없음): 패널을 연다. 응답은 `서브에이전트 패널을 열었어요 (실행 중 N · 완료 M)`.
- `/agent-monitor keep <N>`: 끝난 에이전트 보관 개수. 1~50 정수, 아니면 사용법. `$.store`에 저장해 세션 간 유지.
- `/agent-monitor clear`: 끝난 기록을 지운다. 실행 중 기록은 남긴다.
- `/agent-monitor auto on|off`: 첫 에이전트가 뜰 때 패널을 자동으로 열지. 기본 `on`, 세션 간 유지.
- 그 외 인자: 사용법을 보여준다.

## 구조

```
mods/agent-monitor/
  .claude-plugin/plugin.json   # name·version·description·types
  hooks/hooks.json             # { "modules": ["./register.tsx"] }
  hooks/register.tsx           # 훅 등록, 틱, 상태 읽기/쓰기, 렌더, 명령
  hooks/agents.ts              # 순수 함수 (화면·엔진 의존 없음)
  types/index.d.ts             # $.state 계약
  tests/agent-monitor.test.tsx
```

### `types/index.d.ts`

```ts
export type AgentStatus = 'running' | 'completed' | 'failed' | 'killed'
export type AgentRecord = { /* 위 데이터 절 */ }

declare module 'claude-code' {
  interface PluginState {
    'agent-monitor': {
      records: AgentRecord[]
      now: number          // 틱이 갱신, 렌더가 경과 시간 계산에 사용
      keep: number
      auto: boolean
      autoOpened: boolean  // 이 세션에서 자동으로 연 적이 있는지
    }
  }
}
```

### `hooks/agents.ts`

- `startRecord(input, agentId, now): AgentRecord`
- `addToolCall(records, agentId): AgentRecord[]`: 모르는 id면 그대로 반환.
- `addTokens(records, agentId, usage): AgentRecord[]`
- `finish(records, agentId, reason, now): AgentRecord[]`: `running`인 기록만 바꾼다.
- `reconcile(records, listed, now): AgentRecord[]`: 보정 규칙.
- `trim(records, keep): AgentRecord[]`: 실행 중 전부 + 끝난 것 최근 `keep`개.
- `order(records): AgentRecord[]`: 화면 정렬.
- `columnsFor(width): { description: number; showTokens: boolean; showCalls: boolean }`: 좁은 화면 규칙.
- `formatDuration(ms): string`: `48s`, `2m 14s`, `1h 03m`.
- `formatTokens(n): string`: `950`, `6.2k`, `42.1k`, `1.2M`.
- `parseKeep(arg): number | string`: 성공하면 수, 실패하면 사용법.

## 테스트

`claude plugin test mods/agent-monitor`로 실행한다.

- **`agents.ts` 단위**
  - `addToolCall`·`addTokens`: 추적 중 id만 반영, 모르는 id 무시
  - `finish`: `reason`별 상태, 이미 끝난 기록은 그대로
  - `reconcile`: 목록의 끝난 상태 반영, 목록에서 사라진 `running` → `completed`, 이벤트로 정한 상태 유지
  - `trim`: 실행 중 전부 유지, 끝난 것 `keep`개, `endedAt` 순서
  - `formatDuration`·`formatTokens`: 단위 경계
  - `columnsFor`: 폭별로 설명 축소 → 토큰 제거 → 호출 수 제거
  - `parseKeep`: 정상, 0·51, 숫자 아님
- **훅 통합**
  - `agent.spawn` → 서브에이전트 `tool.call` 2회 → `turn.step`(usage) → `turn.complete`(`answer`): 기록이 `completed`, 호출 2, 토큰 합 일치
  - 메인 루프 `tool.call`(`agentId` 없음)은 세지 않는다
  - `deny`된 spawn은 기록하지 않는다
  - `aborted`·`error` 종료 상태
- **렌더**
  - 빈 상태 문구
  - 머리줄 개수, 실행 중이 위·끝난 것이 아래
  - 높이 초과 시 `… 외 N개`
- **명령**
  - `keep` 정상·거부와 즉시 자르기, `clear`가 실행 중 기록을 남기는지, `auto on|off`의 store 반영

## 문서

- `mods/README.md`에 `## agent-monitor` 절을 추가한다. 무엇을 보여주는지, 명령, 토큰이 비용이 아니라 처리 총량이라는 점, VS Code 확장에서는 패널이 그려지지 않는다는 점을 적는다.
- 루트 `README.md`의 모드 표에 한 줄 추가한다.
