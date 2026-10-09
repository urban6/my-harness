# hooks/

훅 스크립트를 두는 곳입니다. 파일 하나가 훅 하나이며, `settings.json`의 `hooks`에 이벤트(`PreToolUse`, `PostToolUse`, `Stop` 등)와 함께 등록해야 실행됩니다.

`hooks/block-main-push.sh` → `PreToolUse`에서 main 브랜치 직접 푸시 차단
