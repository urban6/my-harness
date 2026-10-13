# superpowers

외부 플러그인 [obra/superpowers](https://github.com/obra/superpowers)를 하네스와 나눠 쓰는 방법. 하네스 자산이 아니며 `skills/`에 복사하지 않는다.

## 기준

```bash
./install.sh install --type plugins superpowers
```

또는 `/plugin install superpowers@claude-plugins-official`.

- 확인 버전: 6.4.1
- 설치 범위: 전역(user)
- 텔레메트리 끄기: 환경변수 `SUPERPOWERS_DISABLE_TELEMETRY=1`

## 사용 원칙

- **하네스 자체 개발**(이 저장소의 mods·hooks·skills·install.sh 등): superpowers 흐름(brainstorming → writing-plans → subagent-driven-development / executing-plans)을 쓴다. 산출물은 `docs/specs/`·`docs/plans/`에 `YYYY-MM-DD-<주제>.md`로 둔다.
- **대상 프로젝트의 백엔드 기능 개발**(설계 → 구현 → 검증 → 테스트): `feature-pm`이 맡는다. superpowers 흐름으로 대체하지 않는다.
- 그 밖의 절차 스킬은 아래 상황별 표를 따른다.
- 커밋·푸시는 `/commit`·`/push`, PR 전 코드 리뷰는 `/pre-pr`, PR 대응은 `/post-pr` 커맨드로 하고, 사용자가 요청할 때만 한다.

## 상황별 스킬

| 상황 | 스킬 |
| --- | --- |
| 버그·실패 테스트의 원인을 찾을 때 | `systematic-debugging` |
| "완료·통과했다"고 말하기 전 | `verification-before-completion` |
| 테스트를 먼저 쓰며 구현할 때 | `test-driven-development` |
| PR 밖에서 받은 리뷰 피드백을 반영할 때 (PR 리뷰는 `/post-pr`) | `receiving-code-review` |
| 서로 독립인 작업 2개 이상을 병렬로 돌릴 때 | `dispatching-parallel-agents` |
| 작업 공간을 격리해야 할 때 | `using-git-worktrees` |
| 스킬을 만들거나 고칠 때 | `writing-skills` |
| superpowers 세션이 꼬인 이유를 볼 때 | `diagnosing-superpowers` |

## 호출 예시

```text
superpowers:systematic-debugging으로 {증상}의 근본 원인부터 찾아줘. 수정은 원인 확정 후에.
```

```text
superpowers:verification-before-completion 기준으로 {작업}이 끝났는지 확인해줘.
```

```text
superpowers:receiving-code-review로 아래 리뷰를 검토하고, 반영할 것과 반박할 것을 나눠줘.
{리뷰 내용}
```

## 하네스 자산과의 경계

| 하네스 | superpowers | 나눠 쓰는 법 |
| --- | --- | --- |
| `feature-pm` · `backend-designer` | `brainstorming` · `writing-plans` · `subagent-driven-development` · `executing-plans` | 백엔드 기능 개발은 하네스, 하네스 자체 개발은 superpowers |
| `debugger` | `systematic-debugging` | 에이전트에 진단을 맡기거나, 메인 세션에서 스킬 절차로 직접 디버깅 |
| `test-writer` | `test-driven-development` | 구현 후 보강은 `test-writer`, 테스트 선행은 스킬 |
| `code-reviewer` · `prompts/code-review.md` · `/post-pr` | `requesting-code-review` · `receiving-code-review` | 리뷰 요청과 PR 리뷰 대응은 하네스, PR 밖 피드백은 `receiving-code-review` |
| `boundary-verifier` | `verification-before-completion` | 겹치지 않음. 경계면 정합성 vs 완료 주장 전 증거 확인 |
| `/commit` · `/push` · `/pre-pr` · `/post-pr` | `finishing-a-development-branch` | 하네스를 쓴다 |

## 주의

- **SessionStart 훅**: 세션 시작·`/clear`·압축 때마다 "스킬부터 호출하라"는 지시가 들어간다. 그래서 명시하지 않아도 brainstorming 등이 끼어들 수 있다. 훅만 끌 수는 없고 플러그인 단위로 켜고 끈다.
- **우선순위**: superpowers 자체 규칙상 CLAUDE.md 지시가 스킬보다 우선한다. 대상 프로젝트에서 기능 개발이 superpowers 흐름으로 새지 않게 하려면 그 프로젝트 CLAUDE.md에 아래를 넣는다.

  ```markdown
  - 기능 개발 오케스트레이션은 `feature-pm`을 따른다. superpowers의 brainstorming·writing-plans 흐름은 사용자가 이름으로 지정했을 때만 쓴다.
  ```

- **프로젝트별 끄기**: 대상 프로젝트 `.claude/settings.json`에 아래를 넣는다.

  ```json
  { "enabledPlugins": { "superpowers@claude-plugins-official": false } }
  ```

- **산출물 경로**: superpowers 기본값은 `docs/superpowers/specs/`·`docs/superpowers/plans/`지만, 이 저장소는 CLAUDE.md 규칙대로 `docs/specs/`·`docs/plans/`를 쓴다. `feature-pm`은 대상 프로젝트의 `_workspace/features/{name}/`에 쓴다.
