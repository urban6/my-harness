# 2차 돌연변이 — 채점 전 예상 (2026-10-10)
- mutA (상품·쿠폰 FOR UPDATE 제거): R10 동시성 — r10_stock_race, r10_coupon_quantity_race (r10_same_user_coupon_race 가능)
- mutB (멱등 지문 검사 제거): R4 — r4_mismatch_body_422, r4_mismatch_user_422, r4_pay_mismatch_422 (+ r11_idempotency_code 가능)
- mutC (최대 할인 상한 무시 + PG 키 매번 새로): r2_rate_max_cap, r2_fixed_max_cap, r5_retry_same_pg_key
- 그 외 항목은 통과해야 함

## 변경 (정답 구현 대비 diff)

### mutA
```diff
diff --color -ru acceptance/reference/src/main/java/com/example/order/coupon/CouponRepository.java mutA/src/main/java/com/example/order/coupon/CouponRepository.java
--- acceptance/reference/src/main/java/com/example/order/coupon/CouponRepository.java	2026-10-09 21:56:16
+++ mutA/src/main/java/com/example/order/coupon/CouponRepository.java	2026-10-10 22:06:24
@@ -40,7 +40,7 @@
     }
 
     public Coupon lock(String code) {
-        return jdbc.queryForObject("SELECT " + COLS + " FROM coupons WHERE code = ? FOR UPDATE", MAPPER, code);
+        return jdbc.queryForObject("SELECT " + COLS + " FROM coupons WHERE code = ?", MAPPER, code);
     }
 
     public void addUsed(String code, long delta) {
diff --color -ru acceptance/reference/src/main/java/com/example/order/product/ProductRepository.java mutA/src/main/java/com/example/order/product/ProductRepository.java
--- acceptance/reference/src/main/java/com/example/order/product/ProductRepository.java	2026-10-09 21:55:55
+++ mutA/src/main/java/com/example/order/product/ProductRepository.java	2026-10-10 22:06:24
@@ -39,7 +39,7 @@
     /** Row lock; callers must lock several products in ascending id order. */
     public Product lock(long id) {
         return jdbc.queryForObject(
-                "SELECT id, name, price, stock, reserved FROM products WHERE id = ? FOR UPDATE", MAPPER, id);
+                "SELECT id, name, price, stock, reserved FROM products WHERE id = ?", MAPPER, id);
     }
 
     public void addReserved(long id, long delta) {
```

### mutB
```diff
diff --color -ru acceptance/reference/src/main/java/com/example/order/idempotency/IdempotencyService.java mutB/src/main/java/com/example/order/idempotency/IdempotencyService.java
--- acceptance/reference/src/main/java/com/example/order/idempotency/IdempotencyService.java	2026-10-09 21:56:51
+++ mutB/src/main/java/com/example/order/idempotency/IdempotencyService.java	2026-10-10 22:06:24
@@ -54,7 +54,7 @@
                 continue; // the previous holder failed and released the key; try to claim it again
             }
             Row row = rows.get(0);
-            if (!row.fingerprint().equals(fingerprint)) {
+            if (false && !row.fingerprint().equals(fingerprint)) {
                 throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_MISMATCH",
                         "Idempotency-Key was already used for a different request");
             }
```

### mutC
```diff
diff --color -ru acceptance/reference/src/main/java/com/example/order/coupon/Coupon.java mutC/src/main/java/com/example/order/coupon/Coupon.java
--- acceptance/reference/src/main/java/com/example/order/coupon/Coupon.java	2026-10-09 21:56:16
+++ mutC/src/main/java/com/example/order/coupon/Coupon.java	2026-10-10 22:06:24
@@ -8,7 +8,7 @@
     /** R2.4: FIXED = value, RATE = floor(subtotal * value / 100); cap by maxDiscountAmount, then by subtotal. */
     public long discountFor(long subtotal) {
         long discount = "FIXED".equals(type) ? value : Math.floorDiv(Math.multiplyExact(subtotal, value), 100L);
-        if (maxDiscountAmount != null) {
+        if (false && maxDiscountAmount != null) {
             discount = Math.min(discount, maxDiscountAmount);
         }
         return Math.min(discount, subtotal);
diff --color -ru acceptance/reference/src/main/java/com/example/order/order/OrderService.java mutC/src/main/java/com/example/order/order/OrderService.java
--- acceptance/reference/src/main/java/com/example/order/order/OrderService.java	2026-10-09 21:58:47
+++ mutC/src/main/java/com/example/order/order/OrderService.java	2026-10-10 22:06:24
@@ -152,7 +152,7 @@
             }
             String paymentId = null;
             if (order.totalPrice() > 0) {
-                PaymentResult pg = gateway.pay(order.id(), order.totalPrice(), cardToken, idemKey);
+                PaymentResult pg = gateway.pay(order.id(), order.totalPrice(), cardToken, java.util.UUID.randomUUID().toString());
                 if (pg.status() == PaymentGatewayClient.PaymentStatus.DECLINED) {
                     releaser.releaseReservation(order);
                     orders.updateStatus(order.id(), OrderStatus.PAYMENT_FAILED);
```
