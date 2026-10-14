# hooks/

훅을 두는 곳입니다. 폴더 하나가 훅 하나이며, `./install.sh install --type hooks [이름]`이 `hook.json`을 `settings.json`의 `hooks`에 병합합니다(`--project`면 `settings.local.json`). `run.sh` 수정은 즉시 반영되고, `hook.json`을 고치면 다시 설치해야 합니다.

```
hooks/<훅 이름>/
  run.sh      # 훅 스크립트
  hook.json   # settings.json의 "hooks"에 병합할 등록 조각. 경로는 __HOOK_DIR__(이 폴더의 절대 경로)로 적는다
```

- `hooks/tdd-guard/` → `PreToolUse(Edit|Write|MultiEdit)`에서 대응 테스트 없이 프로덕션 소스 수정 차단
- `hooks/block-rm-rf/` → `PreToolUse(Bash)`에서 `rm -rf` 류(재귀+강제 삭제) 차단
- `hooks/block-secret-read/` → `PreToolUse(Read|Grep|Bash)`에서 `.env`·`.env.*`·키/인증서 파일 읽기 차단(`.env.example` 등 예시 파일은 허용)
- `hooks/block-prod-db/` → `PreToolUse(Bash)`에서 Production DB 접속 차단(`prod`·`production`·`live` 키워드 + 프로젝트별 `PROD_DB_PATTERN` 정규식)
