# 예시: 투표 게시글 상세

가상 화면이다(마커 01~05). 형식 참고용이며 필드 구성을 그대로 복사하지 않는다.

````
리소스 2개: 투표 상세(GET /vote-posts/{id}), 댓글 목록(GET /vote-posts/{id}/comments)

### GET /vote-posts/{id}
| 필드 | 타입 | null | 근거 | 비고 |
|---|---|---|---|---|
| id | long | N | - | |
| title | string | N | 01 | |
| status | enum(VOTING, …) | N | 02 | 서버 시각 기준. 추정: SCHEDULED, CLOSED |
| voteEndAt | datetime | N | 02 | D-3 계산 원천 |
| result | object | Y | 03 | 종료 전 null("종료 후 공개" 문구) |
| commentCount | integer | N | 05 | |
| viewer.votedChoice | enum(AGREE, DISAGREE) | Y | 03 | 미투표면 null |
| viewer.canVote | boolean | N | 03 | 가정: 로그인 + 미투표 + 기간 내 |

### GET /vote-posts/{id}/comments
| 필드 | 타입 | null | 근거 | 비고 |
|---|---|---|---|---|
| items[].id | long | N | - | |
| items[].author.name | string | N | 05 | |
| items[].content | string | N | 05 | |
| items[].likeCount | integer | N | 05 | |
| items[].viewer.liked | boolean | N | 05 | 빈 하트 |
| nextCursor | string | Y | - | 24건 중 3건 표시 |

확인 필요
1. 투표 후 변경할 수 있나? → viewer.votedChoice 의미, viewer.canVote 조건
2. 종료 후 결과는 득표 수인가, 비율인가? → result 구조
````
