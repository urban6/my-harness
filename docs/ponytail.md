# ponytail

외부 플러그인 [DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail)을 이 하네스와 **별도로** 쓰는 방법. "가장 작은 완결 변경"을 강제하는 규칙 세트(YAGNI, 기존 코드·표준 라이브러리 우선, 요청 안 한 추상화 금지)다. 하네스 자산이 아니며 `skills/`에 복사하지 않는다.

## 기준

두 번에 나눠 입력한다.

```text
/plugin marketplace add DietrichGebert/ponytail
```

```text
/plugin install ponytail@ponytail
```

- 확인 버전: 5.1.0
- 요구 사항: `node`가 PATH에 있어야 훅이 동작한다. 없으면 스킬만 동작한다.
- 공식 배포처는 GitHub `DietrichGebert/ponytail`과 npm `@dietrichgebert/ponytail`뿐이다. 다른 저장소의 복제본은 설치하지 않는다.

## 사용 원칙

- 기본 레벨은 `off`로 두고, 필요한 작업에서만 `/ponytail`로 켠다.
- 리뷰·감사 커맨드(`/ponytail-review`, `/ponytail-audit`)는 이름으로 호출할 때만 쓴다.
- 기능 단위 오케스트레이션은 `feature-pm`, PR 전 점검은 `/pre-pr`이 맡는다. ponytail은 "줄일 코드" 관점의 보조로만 쓴다.

## 레벨

| 레벨 | 동작 |
| --- | --- |
| `off` | 꺼짐 |
| `lite` | 요청대로 만들고, 더 작은 대안은 한 줄로 제안만 한다 |
| `full` | 규칙 전체 적용 (플러그인 기본값) |
| `ultra` | 만들기 전에 요청 자체에서 필요 없는 부분을 되묻는다 |

- 전환: `/ponytail lite|full|ultra|off`. 인자 없이 호출하면 꺼져 있을 때는 기본 레벨로 켜고, 켜져 있을 때는 현재 레벨을 알려 준다.
- 끄기: `/ponytail off`, 또는 메시지 전체를 `stop ponytail`이나 `normal mode`로 보낸다.
- 기본 레벨 지정: 우선순위 순서로 아래 중 하나를 쓴다.
  1. 환경변수 `PONYTAIL_DEFAULT_MODE=off`
  2. `~/.config/ponytail/config.json`에 `{ "defaultMode": "off" }`

## 커맨드

| 커맨드 | 용도 | 파일 수정 |
| --- | --- | --- |
| `/ponytail` | 레벨 전환 | — |
| `/ponytail-review` | 변경분 리뷰 — 버그·보안·부하·테스트 없는 위험 코드·느린 경로·지울 코드. 대상: `uncommitted`·`staged`·`branch`·PR 링크 | 안 함 |
| `/ponytail-audit` | 저장소 전체 감사, 중요도 순 | 안 함 |
| `/ponytail-debt` | `shortcut:` 주석을 모아 미룬 작업 목록으로 정리 | 안 함 |
| `/ponytail-gain` | 벤치마크 절감 수치 표시 | 안 함 |
| `/ponytail-help` | 레벨·커맨드 요약 | 안 함 |

## 호출 예시

```text
/ponytail lite
{작업}. 더 작게 갈 수 있는 부분이 있으면 한 줄로 알려줘.
```

```text
/ponytail-review branch
```

```text
/ponytail-debt
```

## 하네스 자산과의 경계

| 하네스 | ponytail | 나눠 쓰는 법 |
| --- | --- | --- |
| `feature-pm` · `backend-designer` · `backend-impl` | 기본 규칙(`full`·`ultra`) | 설계 산출물이 범위를 정한다. 설계에 있는 항목을 ponytail 규칙으로 빼지 않는다 |
| `code-reviewer` · `/pre-pr` | `/ponytail-review` | 리뷰·판정은 하네스. 과설계·지울 코드를 따로 보고 싶을 때만 `/ponytail-review` |
| `architecture-expert` · `prompts/code-review.md` | `/ponytail-audit` | 구조 진단은 하네스. 저장소 전체의 군더더기 찾기는 `/ponytail-audit` |

## 주의

- **훅 3개**: 켜져 있으면 아래 시점마다 규칙이 주입된다. 훅만 끌 수는 없고 레벨을 `off`로 두거나 플러그인 단위로 끈다.
  - `SessionStart`(시작·재개·`/clear`·압축)
  - `SubagentStart`: **하네스 에이전트에도 들어간다**
  - `UserPromptSubmit`: 레벨 전환 추적
- **서브에이전트 영향**: `feature-pm`이 띄우는 워커에도 규칙이 들어가므로, 기능 개발 중에는 `off`나 `lite`가 안전하다.
- **응답 형식**: 켜져 있으면 응답 끝에 "건너뛴 것·확인 안 한 것·위험" 한두 줄이 붙는다.
- **자동 트리거**: 핵심 스킬 `ponytail`의 description이 "모든 코딩 작업"을 트리거로 잡고 있어서, 레벨이 `off`여도 모델이 스킬을 불러올 수 있다. 막으려면 대상 프로젝트 CLAUDE.md에 아래를 넣는다.

  ```markdown
  - ponytail은 사용자가 `/ponytail`로 켰을 때만 따른다.
  ```

- **프로젝트별 끄기**: 대상 프로젝트 `.claude/settings.json`에 아래를 넣는다.

  ```json
  { "enabledPlugins": { "ponytail@ponytail": false } }
  ```
