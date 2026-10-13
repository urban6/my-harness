# claude-md-management

Anthropic 공식 마켓플레이스(`claude-plugins-official`)의 플러그인으로 CLAUDE.md를 점검·갱신하는 방법. 하네스 자산이 아니며 `skills/`·`commands/`에 복사하지 않는다.

## 설치

```bash
./install.sh install --type plugins claude-md-management
```

또는 `/plugin install claude-md-management@claude-plugins-official`.

- 확인 버전: 1.0.0

## 구성

| 이름 | 종류 | 용도 | 쓰는 때 |
| --- | --- | --- | --- |
| `claude-md-improver` | 스킬 | 저장소의 CLAUDE.md를 모두 찾아 코드베이스와 대조하고, 품질 리포트를 낸 뒤 수정안을 적용 | 주기 점검, 구조·명령이 바뀐 뒤 |
| `/revise-claude-md` | 커맨드 | 현재 세션에서 알게 된 명령·관례·함정을 CLAUDE.md에 한 줄씩 추가 | 세션을 마칠 때 |

- 둘 다 수정안을 먼저 보여 주고 **승인한 파일만** 고친다.

## 호출 예시

```text
CLAUDE.md 점검해줘
```

```text
/revise-claude-md
```

## 주의

- **반영 위치**: `/revise-claude-md`는 팀 공유는 `CLAUDE.md`, 개인용은 `.claude.local.md`로 나눠 제안한다. 이 하네스 템플릿의 CLAUDE.md에는 특정 프로젝트 전용 내용이 들어가지 않게 확인한다.
