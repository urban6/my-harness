# hooks/

훅을 두는 곳입니다. 폴더 하나가 훅 하나이며, `./install.sh install --type hooks [이름]`이 `hook.json`을 `settings.json`의 `hooks`에 병합합니다(`--project`면 `settings.local.json`). `run.sh` 수정은 즉시 반영되고, `hook.json`을 고치면 다시 설치해야 합니다.

```
hooks/<훅 이름>/
  run.sh      # 훅 스크립트
  hook.json   # settings.json의 "hooks"에 병합할 등록 조각. 경로는 __HOOK_DIR__(이 폴더의 절대 경로)로 적는다
```

- `hooks/tdd-guard/` → `PreToolUse(Edit|Write|MultiEdit)`에서 대응 테스트 없이 프로덕션 소스 수정 차단
- `hooks/block-main-push.sh` → `PreToolUse`에서 main 브랜치 직접 푸시 차단 (폴더 구조로 이전 전, install.sh 대상 아님)
