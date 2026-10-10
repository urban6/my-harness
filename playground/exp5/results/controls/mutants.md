# 돌연변이 검증 — 실패 예상 (채점 전 기록, 2026-10-11)

정답 구현(`acceptance/reference-t3`·`reference-t4`)에 결함을 하나씩 넣고, 아래 케이스만 실패하고 나머지(회귀 포함)는 전부 통과하는지 본다. 예상은 채점 전에 적었고 채점 후 고치지 않는다.

## T3

| ID | 넣은 결함 | 실패 예상 케이스 |
|---|---|---|
| t3-m1 계산 | 누적 기준 안분(P4.5) 대신 요청마다 `floor(totalPrice × 이번 정가 / subtotal)` | `p4_full_refund_sums_total` |
| t3-m2 순서 | 카드 먼저(P4.6) 대신 포인트 먼저 돌려줌 | `p4_card_first_then_points` |
| t3-m3 PG 키 | PG 환불 `Idempotency-Key`를 호출마다 새 UUID로 (P4.7·P5.1) | `p4_partial_refund_basic` · `p4_pg_timeout_then_retry` · `p5_cancel_partially_refunded` · `p5_cancel_paid_with_points` |
| t3-m4 상태 | 부분 환불을 `SHIPPED`에서도 허용 (P4.3·P5.2) | `p4_invalid_state_409` · `p5_ship_from_partial` |

## T4

| ID | 넣은 결함 | 실패 예상 케이스 |
|---|---|---|
| t4-m1 PG 키 | PG 결제 `Idempotency-Key`에 테넌트 접두어를 빼고 클라이언트 키 그대로 (M6.1) | `m6_pg_key_prefixed` · `m6_retry_same_pg_key` · `m6_same_key_charged_separately` |
| t4-m2 목록 | 주문 목록 쿼리의 테넌트 조건 무력화 (M7.1) | `m7_list_isolation` · `m7_paging_within_tenant` · (해석) `i_cursor_cross_tenant` |
| t4-m3 잠금 | 결제·취소·배송이 쓰는 주문 잠금 조회의 테넌트 조건 무력화 (M2.2) | `m2_actions_cross_tenant_404` |
| t4-m4 사용자 | 쿠폰 1인 사용 중 검사의 테넌트 조건 무력화 (M4.2) | `m4_one_per_user_per_tenant` |
