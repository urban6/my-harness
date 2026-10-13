# codex

외부 플러그인 [openai/codex-plugin-cc](https://github.com/openai/codex-plugin-cc)로 Claude Code가 만든 설계·구현을 Codex에 교차 검증시키는 방법. 하네스 자산이 아니며 `skills/`·`agents/`에 복사하지 않는다.

## 설치

```bash
./install.sh install --type plugins codex
```

또는 `/plugin marketplace add openai/codex-plugin-cc` 후 `/plugin install codex@openai-codex`.

```text
/codex:setup
```

- 확인 버전: 1.0.6
- 요구 사항: Codex CLI 설치(`npm install -g @openai/codex`)와 로그인(`codex login`). `/codex:setup`이 둘 다 점검한다.

## 사용 흐름: Claude 설계 → Codex 검증

1. Claude Code로 설계 문서를 만든다(`feature-pm`·`backend-designer`). **커밋하지 않는다.**
2. 미커밋 설계 문서를 Codex가 반박하게 한다.

   ```text
   /codex:adversarial-review --scope working-tree 설계 문서만 검토. API 상태코드와 DB 제약 정합성, 동시성·멱등성·마이그레이션 위험 위주로
   ```

3. 결과를 Claude에 붙여 "반영할 것과 반박할 것을 분류해 줘"로 반영한다. 지적을 무조건 수용하지 않는다.
4. `needs-attention`이 없어질 때까지 2~3을 반복하고, 설계를 커밋한 뒤 구현으로 넘어간다.
5. 구현 후 브랜치 전체를 일반 리뷰한다.

   ```text
   /codex:review --base main
   ```

## 프롬프트 예시: 설계 직후

```text
/codex:adversarial-review --scope working-tree --background 방금 작성한 주문 기능 설계(01_api_design.md, 02_db_design.md)만 검토해줘. 구현 코드는 아직 없다. 1) API 응답 필드와 DB 컬럼·nullable이 서로 맞는지 2) DB 제약 위반이 어떤 HTTP 상태코드로 나가는지 빠짐없이 정의됐는지 3) 동시 주문·중복 요청(멱등성)·재고 차감 순서에서 깨지는 시나리오 4) 마이그레이션이 기존 데이터와 충돌할 위험. 각 지적에는 문서의 해당 라인과 구체적인 수정안을 붙여줘.
```

- "구현 코드는 아직 없다"를 넣어야 "코드 없음" 지적이 쏟아지지 않는다.
- 번호로 적은 관점은 가중치가 높게 반영된다. 기능에 맞게 바꾼다.
- 끝나면 `/codex:result`로 결과를 받아 Claude에 넘긴다.

## 커맨드

| 커맨드 | 용도 |
| --- | --- |
| `/codex:adversarial-review [중점]` | 설계·접근 방식을 반박하는 리뷰. 중점을 자유 텍스트로 지정 |
| `/codex:review` | git 변경의 일반 코드 리뷰. 중점 지정 불가 |
| `/codex:status` · `/codex:result` · `/codex:cancel` | 백그라운드 작업 확인·결과·취소 |

리뷰 옵션: `--scope working-tree|branch`, `--base <ref>`, `--wait|--background`.

## 주의

- 리뷰 대상은 git 변경분이다. 관계없는 미커밋 변경이 있으면 같이 리뷰되므로 정리하거나 별도 브랜치에서 한다.
- 미커밋(untracked) 파일은 24KB를 넘으면 리뷰에서 빠진다. 큰 설계 문서는 나누거나, 커밋 후 `--scope branch --base main`으로 리뷰한다.
- 검증에는 `/codex:rescue`를 쓰지 않는다. 기본이 쓰기 모드라 파일을 직접 고친다.
- review 계열은 사람만 호출할 수 있다(`disable-model-invocation`). `feature-pm`이 자동으로 부를 수 없으므로 단계 사이에 직접 입력한다.
