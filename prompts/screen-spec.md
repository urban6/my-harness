# 화면 캡처 → API 응답 데이터

## A — 응답 데이터 추출

```text
/screen-spec-analyzer
{화면명} 화면에 필요한 API 응답 데이터를 뽑아줘.

- 화면: {Figma 프레임 URL 또는 첨부 캡처}
- 추가 상태: {빈 상태 / 종료 상태 / 작성자 화면 등 첨부, 없으면 생략}
- 화면정의서 설명: {번호별 설명 텍스트, 없으면 생략}
- 저장: {docs/screen-specs/{screen-id}.md, 없으면 생략}
```

예시:

```text
/screen-spec-analyzer
투표 상세 화면에 필요한 API 응답 데이터를 뽑아줘.

- 화면: 첨부 캡처
```

## B — 확인 질문 답변

```text
확인 필요 질문에 답할게. 필드 표를 갱신해줘.

1. {답}
2. {답}
```

## C — API 설계 (design)

```text
/screen-spec-analyzer design
{위 필드 표 / 저장 경로} 기준으로 API 계약과 DTO를 설계해줘.
구현 코드는 작성하지 마.
```

## D — 기존 API 검증 (verify)

```text
/screen-spec-analyzer verify
{위 필드 표 / 저장 경로} 기준으로 {메서드 경로}를 검증해줘.

- 응답 샘플: {JSON 붙여넣기 또는 없음}
- 로컬 서버: {http://localhost:8080, GET 호출 허용 / 없음}
```

예시:

```text
/screen-spec-analyzer verify
위 필드 표 기준으로 GET /api/vote-posts/{id}를 검증해줘.

- 응답 샘플: 없음
- 로컬 서버: http://localhost:8080, GET 호출 허용
```
