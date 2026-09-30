# 예시: 투표 게시글 상세 (analyze 모드, 일부 발췌)

가상의 화면이다. 마커 01~05, 설명 패널 있음. 형식 참고용이며 필드 구성을 그대로 복사하지 않는다.

## 1단계 인벤토리

| 영역 | 요소 | 캡처 표시값 | 분류 | 비고 |
|---|---|---|---|---|
| 01 | 게시글 제목 | "점심 메뉴 투표" | DATA | |
| 02 | 마감 배지 | "D-3" | DERIVED | 원천: 마감 일시 |
| 03 | 투표 버튼 | "투표하기" (활성) | ACTION, USER_CTX | 이미 투표했으면 비활성으로 추정 |
| 03 | 안내 문구 | "투표 종료 후 결과가 공개됩니다" | STATIC, RULE | 종료 전 집계 비노출 |
| 05 | 댓글 수 | "댓글 24" | DATA | 목록은 3건만 표시 → 페이지네이션 필요 |

## 3-1 리소스 트리

```yaml
screen: vote-post-detail
resources:
  - name: votePost
    source: TBD
    fields:
      - path: id
        type: long
        nullable: false
        category: TECHNICAL
        example: 1024
        evidence: "-"
        confidence: high
      - path: title
        type: string
        nullable: false
        category: DATA
        example: "점심 메뉴 투표"
        evidence: "01"
        confidence: high
      - path: voteEndAt
        type: datetime
        nullable: false
        category: DATA
        example: "2026-10-03T18:00:00+09:00"
        evidence: "02"
        confidence: medium
      - path: status
        type: enum
        values: [IN_PROGRESS]
        inferred: [CLOSED]
        nullable: false
        category: DERIVED
        example: IN_PROGRESS
        evidence: "02"
        confidence: medium
      - path: viewer.hasVoted
        type: boolean
        nullable: false
        category: USER_CTX
        example: false
        evidence: "03"
        confidence: low
```

## 3-3 규칙

| 규칙 | 근거 | 강제 위치 |
|---|---|---|
| 종료 전에는 선택지별 득표 수를 응답에서 제외 | 03 안내 문구 | 서버 필수 |
| 1인 1회 투표 | 03 버튼 비활성 추정 | 서버 필수 |

## 3-4 확인 필요 질문

1. 투표 후 선택 변경·취소가 가능한가? → 답에 따라 바뀌는 것: 변경 엔드포인트(PUT/DELETE) 유무, `viewer.selectedOptionId` 필드 추가
2. D-day 기준 시각은 KST 자정인가, 마감 시각 기준 24시간 단위인가? → 답에 따라 바뀌는 것: 없음(클라 계산) / 서버가 `dDay`를 내려야 하는지 여부
