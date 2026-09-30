# 화면정의서 → API

## A — 명세 추출 (analyze)

```text
/screen-spec-analyzer analyze
{화면명} 화면을 분석해줘.

- 화면: {Figma 프레임 URL 또는 첨부 캡처}
- 추가 상태: {빈 상태 / 종료 상태 / 작성자 화면 등 첨부, 없으면 생략}
- 화면정의서 설명:
  {번호별 설명 텍스트 붙여넣기}

명세는 docs/screen-specs/{screen-id}.md로 저장해줘.
```

## B — 질문 답변 후 명세 갱신

```text
docs/screen-specs/{screen-id}.md의 확인 질문에 답할게. 명세를 갱신해줘.

1. {답}
2. {답}
```

## C — API 설계 (design)

```text
/screen-spec-analyzer design
docs/screen-specs/{screen-id}.md 기준으로 API 계약과 DTO를 설계해줘.
구현 코드는 작성하지 마.
```

## D — 기존 API 검증 (verify)

```text
/screen-spec-analyzer verify
docs/screen-specs/{screen-id}.md 기준으로 {메서드 경로}를 검증해줘.

- 응답 샘플: {JSON 붙여넣기 또는 없음}
- 로컬 서버: {http://localhost:8080, GET 호출 허용 / 없음}
```

예시:

```text
/screen-spec-analyzer verify
docs/screen-specs/vote-post-detail.md 기준으로 GET /api/vote-posts/{id}를 검증해줘.

- 응답 샘플: 없음
- 로컬 서버: http://localhost:8080, GET 호출 허용
```
