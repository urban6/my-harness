# 대화형 설치 설계

`./install.sh`를 인자 없이 실행하면 대상·유형·항목을 차례로 골라 설치하는 대화형 모드. README 설치 섹션을 세 줄로 줄인다.

## 목적과 범위

- **목적**: 플래그 조합을 몰라도 `./install.sh` 하나로 원하는 자산만 설치한다.
- **성공 기준**
  - `./install.sh` 실행 후 Enter만 누르면 전역에 전체 자산이 설치된다(현재 `./install.sh install`과 같은 결과).
  - 유형·항목을 번호로 빼거나 넣을 수 있고, 설치 전에 미리보기와 확인을 거친다.
  - 기존 비대화형 명령(`install`·`uninstall`·`list` + 플래그)의 동작과 출력이 바뀌지 않는다.
  - 추가 의존성이 없고 macOS 기본 bash 3.2에서 동작한다.
- **범위 밖**: 대화형 제거·현황 보기(기존 `uninstall`·`list` 사용), 프리셋(묶음) 선택, 방향키 UI, fzf·gum 연동, 이미 설치된 항목을 기본 선택에 반영.

## 진입점

| 실행 | 동작 |
|---|---|
| `./install.sh` (stdin이 터미널) | 대화형 설치 |
| `./install.sh` (stdin이 터미널 아님) | 지금처럼 도움말 출력 |
| `./install.sh -h` · `--help` · `help` | 도움말 출력 |
| `./install.sh install\|uninstall\|list ...` | 기존 동작 그대로 |

터미널 여부는 `[[ -t 0 ]]`로 판단한다. 테스트에서는 `HARNESS_INTERACTIVE=1`로 이 검사를 건너뛴다.

## 흐름

```
$ ./install.sh
설치 대상
  1) 전역 ~/.claude (기본)
  2) 현재 프로젝트 /path/to/cwd/.claude
  3) 다른 프로젝트 경로 입력
선택 [1]:

설치할 유형 — 번호로 토글, a=전체, n=없음, Enter=확정
  [x] 1 agents (10)
  [x] 2 commands (4)
  ...
  [x] 7 plugins (3)
선택:

── agents ──  번호로 토글, a=전체, n=없음, Enter=확정
  [x] 1 architecture-expert
  [x] 2 backend-designer
  ...
선택:
(고른 유형마다 반복)

── 미리보기 ──
(기존 dry-run 출력 그대로)
같은 명령: ./install.sh install --type agents --type skills ...

진행할까요? [Y/n]:
```

### 단계별 규칙

1. **대상**
   - `1` 또는 Enter: 전역. `2`: 현재 디렉터리. `3`: 경로를 입력받는다. 존재하지 않는 경로면 다시 묻는다.
   - 결과는 `--global` / `--project PATH`를 준 것과 같게 `TARGET_BASE`·`SETTINGS_NAME`을 설정한다.
   - 이 단계는 인자 파싱 직후, `SETTINGS_FILE`·`PLUGIN_SCOPE` 계산 전에 실행한다. 대상에 따라 그 값들이 정해지기 때문이다.
2. **유형**
   - 항목이 하나 이상 있는 유형만 보여 준다. 괄호 안 숫자는 항목 수다.
   - 대상이 프로젝트면 `mods`를 목록에서 뺀다(전역 전용).
   - `jq`가 없으면 `hooks`·`mods`·`plugins`를, `claude` CLI가 없으면 `plugins`를 `(jq 필요)`·`(claude CLI 필요)`로 표시하고 기본 해제한다. 사용자가 켜면 기존 `require_*`가 설치 단계에서 에러를 낸다.
   - 기본은 전부 선택이다. 아무것도 고르지 않고 확정하면 "선택된 항목이 없습니다"를 출력하고 종료한다(exit 0).
3. **항목**: 고른 유형마다 항목 목록을 같은 토글 방식으로 보여 준다. 항목 목록은 `for_each_asset`에 이름을 모으는 콜백을 넘겨 만든다. 그래서 `README.md`·`*-workspace` 제외 규칙이 그대로 적용된다. 한 유형에서 전부 해제하면 그 유형은 건너뛴다.
4. **미리보기**: `DRY_RUN=1`로 설치를 한 번 돌린다. 이어서 같은 동작을 하는 비대화형 명령 한 줄을 출력한다.
   - 고른 유형마다 `--type`을 붙인다.
   - 모든 유형에서 전부 선택했으면 이름을 생략한다. 하나라도 일부만 골랐으면 고른 항목 이름을 전부 나열한다.
   - 대상이 프로젝트면 `--project PATH`를 붙인다.
5. **충돌**: 미리보기에서 CONFLICT가 하나라도 나왔을 때만 "기존 항목을 .bak으로 백업하고 교체할까요? [y/N]"를 묻는다. `y`면 `FORCE=1`이고, 같은 명령에도 `--force`를 붙인다.
6. **확인**: `[Y/n]`. `n`이면 아무것도 바꾸지 않고 종료한다. 그 외에는 카운터를 0으로 초기화하고 `DRY_RUN=0`으로 다시 돌린 뒤 요약을 출력한다.

### 토글 입력

- 공백으로 구분한 번호(`1 3 5`)는 각 번호의 선택 상태를 뒤집는다.
- `a`는 전체 선택, `n`은 전체 해제다.
- Enter(빈 입력)는 확정한다.
- 범위를 벗어난 번호나 알 수 없는 토큰은 "잘못된 입력: X"를 출력하고 무시한다.
- 입력 후에는 목록을 다시 출력한다.
- 입력 중 EOF(Ctrl-D)는 취소로 보고 exit 1로 종료한다.

## 구현

- 위치: `install.sh` 안에 `# --- interactive` 블록을 추가한다(예상 100~150줄). 함수는 `interactive_target`, `interactive_toggle`, `interactive_select`, `interactive_run` 정도로 나눈다.
- 인자 파싱의 `""` 분기만 바꾼다. 터미널이면 `CMD=install`, `INTERACTIVE=1`로 두고 `interactive_target`을 호출한다. 아니면 지금처럼 도움말을 출력한다.
- 실행부의 `install)` 분기에서 `INTERACTIVE=1`이면 `interactive_run`을 호출한다. 이 함수는 유형마다 `SEL_TYPES=(유형)`, `SEL_NAMES=(그 유형에서 고른 항목)`으로 `for_each_asset do_install`을 한 번씩 돌린다. 이름 필터는 유형을 구분하지 않으므로, 이렇게 유형별로 나눠 돌려야 다른 유형의 같은 이름이 섞이지 않는다.
- 기존 함수(`link_one`, `do_install_*`, `for_each_asset` 등)는 고치지 않는다.
- bash 3.2 호환: 연관 배열(`declare -A`)과 `${var,,}`를 쓰지 않는다. 선택 상태는 인덱스 배열로 관리한다.
- `set -u` 아래에서 빈 배열 확장 오류가 나지 않도록 `${arr[@]+"${arr[@]}"}` 형태를 쓴다.

## README

설치 섹션을 다음으로 바꾼다.

````markdown
## 설치

```bash
./install.sh            # 대화형: 대상·유형·항목을 골라 설치
./install.sh list       # 설치 상태 확인
./install.sh uninstall  # 우리 심링크만 제거
```

스크립트에서 쓰거나 세부 옵션(`--type`, `--project`, `--force`, `--dry-run` 등)이 필요하면 `./install.sh -h`를 본다.
````

지금 있는 플래그 목록은 README에서 지운다. 같은 내용이 `install.sh` 상단 주석(= `-h` 출력)에 이미 있다. 그 주석의 사용법에 `./install.sh` (인자 없음) = 대화형 한 줄을 추가한다. `CLAUDE.md`의 명령 섹션은 바꾸지 않는다.

## 검증

1. **시나리오 테스트**: 임시 `HOME`과 임시 프로젝트 디렉터리를 만든다. `HARNESS_INTERACTIVE=1`로 `printf` 입력을 파이프로 넣어 다음을 확인한다.
   - Enter만 입력 → 전역에 전체 설치. `./install.sh list` 결과가 비대화형 `./install.sh install` 결과와 같다(plugins는 `claude` CLI가 있을 때만 비교).
   - 프로젝트 대상 → 유형 목록에 `mods`가 없다.
   - 유형 일부 해제, 항목 일부 해제 → 고른 것만 링크되고 "같은 명령" 줄이 기대값과 같다.
   - 확인에서 `n` → 대상 디렉터리에 변경이 없다.
   - 잘못된 번호·EOF 처리.
2. **회귀**: 변경 전후로 `./install.sh install --dry-run`, `./install.sh install --project <tmp> --dry-run`, `./install.sh list` 출력이 같다.
3. **비터미널**: `./install.sh < /dev/null`이 도움말을 출력하고 exit 0으로 끝난다.
4. **bash 3.2**: `/bin/bash`(macOS 기본)로 1번을 실행한다.
