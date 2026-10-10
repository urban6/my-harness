"""T3 숨긴 테스트 — tasks/t3/change-request.md (포인트 혼합 결제·부분 환불).

- 케이스마다 docstring에 근거 규칙 번호를 단다. 명세 내(spec) 케이스는 change-request.md·feature.md 문구에서만 도출한다.
- 명세 해석(interp) 케이스는 명세에 없는 상식적 기대다. 주 지표에 넣지 않는다.
- 표준 라이브러리만 사용한다. 헬퍼는 regression.py(2차 케이스)를 재사용한다.
"""
import time
from urllib.parse import quote

import regression as rg
from regression import Fail, call, expect_eq, expect_int, expect_problem, expect_status, items_of, new_key, \
    new_product, new_user, product, coupon, new_coupon, get_order, parse_iso, sleep_until, concurrently

EXTRA_ORDER_FIELDS = ("pointAmount", "cardAmount", "refundedAmount")


# ---------- helpers ----------

def grant(user, amount):
    return call("POST", f"/api/points/{quote(user, safe='')}/grants", {"amount": amount})


def balance(user):
    r = call("GET", f"/api/points/{quote(user, safe='')}")
    b = rg.fixture(r, 200, "points get")
    expect_int(b.get("balance"), "balance")
    return b["balance"]


def user_with(points):
    u = new_user()
    if points:
        rg.fixture(grant(u, points), 200, "grant")
    return u


def create(items, user=None, coupon_code=None, use_points=None, key=None, headers=None):
    body = {"items": items}
    if coupon_code is not None:
        body["couponCode"] = coupon_code
    if use_points is not None:
        body["usePoints"] = use_points
    h = {"X-User-Id": user or new_user(), "Idempotency-Key": key or new_key()}
    h.update(headers or {})
    return call("POST", "/api/orders", body, headers=h)


def order(items, user=None, coupon_code=None, use_points=None):
    return rg.fixture(create(items, user, coupon_code, use_points), 201, "order create")


def paid(items, user=None, coupon_code=None, use_points=None, token="tok_ok"):
    o = order(items, user, coupon_code, use_points)
    return rg.fixture(rg.pay(o["id"], token), 200, "pay")


def refund(oid, pairs, key=None, headers=None):
    h = {"Idempotency-Key": key or new_key()}
    h.update(headers or {})
    return call("POST", f"/api/orders/{oid}/refunds", {"items": items_of(*pairs)}, headers=h)


def check_t3_order(obj, what):
    rg.check_order(obj, what)
    rg.expect_fields(obj, EXTRA_ORDER_FIELDS, what)
    for f in EXTRA_ORDER_FIELDS:
        expect_int(obj[f], f"{what}.{f}")
    for i, item in enumerate(obj["items"]):
        rg.expect_fields(item, ("refundedQuantity",), f"{what}.items[{i}]")
        expect_int(item["refundedQuantity"], f"{what}.items[{i}].refundedQuantity")
    if obj["cardAmount"] != obj["totalPrice"] - obj["pointAmount"]:
        raise Fail("field", f"{what}: cardAmount {obj['cardAmount']} != totalPrice - pointAmount")


def refunded_qty(o, pid):
    for item in o["items"]:
        if rg.same_id(item["productId"], pid):
            return item["refundedQuantity"]
    raise Fail("field", f"product {pid} not in order")


def refund_calls(oid):
    return [c for c in rg.pg_calls(oid, "refund") if not c.get("replayed")]


def applied_refund_sum(oid):
    return sum(c["amount"] or 0 for c in refund_calls(oid) if c.get("applied"))


def cumulative(total, subtotal, gross):
    """P4.5 누적 환불액"""
    return total * gross // subtotal


# ---------- P1 포인트 계정 ----------

def p1_grant_and_get():
    """P1.1 P1.3"""
    u = new_user()
    r = call("GET", f"/api/points/{u}")
    expect_status(r, 200, "never granted")
    expect_eq(r.body.get("balance") if isinstance(r.body, dict) else None, 0, "initial balance")
    r = grant(u, 1500)
    expect_status(r, 200, "grant")
    expect_eq((str(r.body.get("userId")), r.body.get("balance")), (u, 1500), "grant body")
    expect_eq(rg.fixture(grant(u, 500), 200, "grant 2")["balance"], 2000, "balance after second grant")
    expect_eq(balance(u), 2000, "get balance")


def p1_grant_invalid_400():
    """P1.2"""
    u = new_user()
    for bad in (0, -1, 10_000_001, 1.5, "100", None):
        body = {} if bad is None else {"amount": bad}
        expect_problem(call("POST", f"/api/points/{u}/grants", body), 400, f"amount {bad!r}", "VALIDATION_ERROR")
    expect_status(grant(" ", 100), 400, "blank userId")
    expect_status(grant("u" * 51, 100), 400, "51-char userId")
    expect_eq(balance(u), 0, "balance unchanged after rejects", "atomicity")
    expect_eq(rg.fixture(grant(u, 1), 200, "grant 1")["balance"], 1, "lower bound")
    expect_eq(rg.fixture(grant(u, 10_000_000), 200, "grant max")["balance"], 10_000_001, "upper bound")
    expect_status(grant("u" * 50, 100), 200, "50-char userId")


def p1_balance_excludes_used():
    """P1.4 P2.4"""
    u = user_with(1000)
    o = order(items_of((new_product(price=1000), 1)), u, use_points=300)
    expect_eq(balance(u), 700, "after order", "atomicity")
    expect_status(rg.pay(o["id"]), 200, "pay")
    expect_eq(balance(u), 700, "after pay", "atomicity")


# ---------- P2 주문에 포인트 사용 ----------

def p2_order_fields():
    """P2.1 P2.5 R2.4"""
    u = user_with(5000)
    pid = new_product(price=1000, stock=10)
    r = create(items_of((pid, 3)), u, new_coupon(value=500), use_points=1000)
    expect_status(r, 201, "create with points")
    check_t3_order(r.body, "create body")
    b = r.body
    expect_eq((b["subtotal"], b["discount"], b["totalPrice"], b["pointAmount"], b["cardAmount"], b["refundedAmount"]),
              (3000, 500, 2500, 1000, 1500, 0), "amounts")
    expect_eq(refunded_qty(b, pid), 0, "refundedQuantity")
    check_t3_order(get_order(b["id"]), "get")
    plain = order(items_of((new_product(price=700), 2)))
    check_t3_order(plain, "no usePoints")
    expect_eq((plain["pointAmount"], plain["cardAmount"]), (0, 1400), "default usePoints 0")


def p2_use_points_invalid_400():
    """P2.1 R3.4"""
    u = user_with(1000)
    pid = new_product(stock=10)
    for bad in (-1, 1.5, "100"):
        expect_problem(create(items_of((pid, 1)), u, use_points=bad), 400, f"usePoints {bad!r}", "VALIDATION_ERROR")
    expect_eq(balance(u), 1000, "balance", "atomicity")
    rg.check_product(product(pid), "product", reserved=0)


def p2_exceed_total_409():
    """P2.2 P5.4"""
    u = user_with(5000)
    pid = new_product(price=1000, stock=10)
    expect_problem(create(items_of((pid, 1)), u, use_points=1001), 409, "points > total", "POINTS_EXCEED_TOTAL")
    expect_eq(balance(u), 5000, "balance", "atomicity")
    rg.check_product(product(pid), "product", reserved=0)
    o = order(items_of((pid, 1)), u, use_points=1000)
    expect_eq((o["pointAmount"], o["cardAmount"]), (1000, 0), "points == total")


def p2_insufficient_409():
    """P2.2 P2.4 P5.4"""
    u = user_with(100)
    pid, code = new_product(stock=10), new_coupon(value=100)
    expect_problem(create(items_of((pid, 1)), u, code, use_points=200), 409, "insufficient points",
                   "INSUFFICIENT_POINTS")
    rg.check_product(product(pid), "product", reserved=0)
    expect_eq(coupon(code)["usedCount"], 0, "usedCount", "atomicity")
    expect_eq(balance(u), 100, "balance", "atomicity")


def p2_precedence():
    """P2.3 C3"""
    u = user_with(100)
    short = new_product(price=1000, stock=1)
    expect_eq(create(items_of((short, 2)), u, use_points=5000).code, "INSUFFICIENT_STOCK", "stock before points",
              "code")
    min_code = new_coupon(value=100, minOrderAmount=1_000_000)
    expect_eq(create(items_of((new_product(), 1)), u, min_code, use_points=5000).code, "COUPON_NOT_APPLICABLE",
              "coupon before points", "code")
    expect_eq(create(items_of((new_product(price=1000), 1)), u, use_points=2000).code, "POINTS_EXCEED_TOTAL",
              "exceed before insufficient", "code")


def p2_restore_on_cancel():
    """P2.6"""
    u = user_with(1000)
    o = order(items_of((new_product(price=2000), 1)), u, use_points=800)
    expect_eq(balance(u), 200, "after order")
    r = rg.action(o["id"], "cancel")
    expect_status(r, 200, "cancel")
    expect_eq(r.body.get("status"), "CANCELLED", "status")
    expect_eq(balance(u), 1000, "restored on cancel", "atomicity")


def p2_restore_on_decline():
    """P2.6 P3.3"""
    u = user_with(1000)
    o = order(items_of((new_product(price=2000), 1)), u, use_points=800)
    expect_status(rg.pay(o["id"], token="tok_decline"), 402, "declined")
    expect_eq(get_order(o["id"])["status"], "PAYMENT_FAILED", "status")
    expect_eq(balance(u), 1000, "restored on decline", "atomicity")


def p2_restore_on_expiry():
    """P2.6 R6.2 (ttl 단계)"""
    u = user_with(1000)
    pid = new_product(price=2000, stock=5)
    o = order(items_of((pid, 1)), u, use_points=900)
    expect_eq(balance(u), 100, "after order")
    sleep_until(parse_iso(o["expiresAt"], "expiresAt"), 2.5)
    expect_eq(balance(u), 1000, "restored on expiry (points read before order)", "timing")
    expect_eq(get_order(o["id"])["status"], "EXPIRED", "status")


def p2_idempotency_points():
    """P2.7 R4.2 R4.3"""
    u = user_with(1000)
    pid, key = new_product(stock=10), new_key()
    first = create(items_of((pid, 1)), u, use_points=300, key=key)
    expect_status(first, 201, "first")
    again = create(items_of((pid, 1)), u, use_points=300, key=key)
    expect_status(again, 201, "replay")
    expect_eq(str(again.body.get("id")), str(first.body["id"]), "replayed id", "idempotency")
    expect_eq(balance(u), 700, "points deducted once", "idempotency")
    expect_problem(create(items_of((pid, 1)), u, use_points=400, key=key), 422, "usePoints differs",
                   "IDEMPOTENCY_KEY_MISMATCH")


# ---------- P3 결제 금액 ----------

def p3_pg_amount_is_card():
    """P3.1"""
    u = user_with(1000)
    o = order(items_of((new_product(price=1500), 2)), u, new_coupon(value=500), use_points=1000)
    expect_status(rg.pay(o["id"]), 200, "pay")
    calls = rg.pg_calls(o["id"])
    expect_eq([c["amount"] for c in calls], [1500], "PG amount = cardAmount", "pg")


def p3_zero_card_no_pg():
    """P3.2"""
    u = user_with(5000)
    pid = new_product(price=1200, stock=5)
    o = order(items_of((pid, 2)), u, use_points=2400)
    r = rg.pay(o["id"])
    expect_status(r, 200, "pay with cardAmount 0")
    expect_eq(r.body.get("status"), "PAID", "status")
    expect_eq(rg.pg_calls(o["id"]), [], "PG not called", "pg")
    rg.check_product(product(pid), "product", stock=3, reserved=0)


def p3_pg_error_keeps_points():
    """P3.3 R5.6"""
    u = user_with(1000)
    o = order(items_of((new_product(price=2000), 1)), u, use_points=500)
    expect_status(rg.pay(o["id"], token="tok_error"), 503, "PG 500")
    expect_eq(get_order(o["id"])["status"], "PENDING_PAYMENT", "status", "atomicity")
    expect_eq(balance(u), 500, "points unchanged by PG failure", "atomicity")


# ---------- P4 부분 환불 ----------

def p4_partial_refund_basic():
    """P4.1 P4.5 P4.7 P4.9"""
    a, b = new_product(price=1000, stock=10), new_product(price=3000, stock=10)
    code = new_coupon(value=1000)
    o = paid(items_of((a, 2), (b, 1)), coupon_code=code)
    expect_eq((o["subtotal"], o["totalPrice"], o["cardAmount"]), (5000, 4000, 4000), "fixture amounts")
    key = new_key()
    r = refund(o["id"], [(a, 1)], key)
    expect_status(r, 200, "partial refund")
    check_t3_order(r.body, "refund body")
    expect_eq((r.body["status"], r.body["refundedAmount"], refunded_qty(r.body, a), refunded_qty(r.body, b)),
              ("PARTIALLY_REFUNDED", 800, 1, 0), "after refund")
    rg.check_product(product(a), "product a", stock=9, reserved=0)
    rg.check_product(product(b), "product b", stock=9, reserved=0)
    expect_eq(coupon(code)["usedCount"], 1, "coupon still used", "atomicity")
    calls = refund_calls(o["id"])
    expect_eq([(c["amount"], c["idempotencyKey"]) for c in calls], [(800, key)], "PG refund request", "pg")


def p4_full_refund_sums_total():
    """P4.5 P4.9 R2.6"""
    a, b = new_product(price=999, stock=10), new_product(price=1001, stock=10)
    code = new_coupon(type="RATE", value=7)
    o = paid(items_of((a, 1), (b, 2)), coupon_code=code)
    sub, total = o["subtotal"], o["totalPrice"]
    expect_eq((sub, total), (3001, 3001 - 3001 * 7 // 100), "fixture amounts")
    gross, refunded = 0, 0
    for step, (pid, price) in enumerate(((b, 1001), (a, 999), (b, 1001))):
        before = cumulative(total, sub, gross)
        gross += price
        expected = cumulative(total, sub, gross) - before
        r = refund(o["id"], [(pid, 1)])
        expect_status(r, 200, f"refund step {step}")
        refunded += expected
        expect_eq(r.body["refundedAmount"], refunded, f"refundedAmount step {step}")
        expect_eq(refund_calls(o["id"])[-1]["amount"], expected, f"PG amount step {step}", "pg")
    expect_eq(refunded, total, "sum of refunds")
    final = get_order(o["id"])
    expect_eq((final["status"], final["refundedAmount"]), ("REFUNDED", total), "final")
    expect_eq(coupon(code)["usedCount"], 0, "coupon restored on full refund", "atomicity")
    rg.check_product(product(a), "product a", stock=10)
    rg.check_product(product(b), "product b", stock=10)


def p4_card_first_then_points():
    """P4.6 P4.7"""
    u = user_with(3000)
    a, b = new_product(price=2000, stock=5), new_product(price=2000, stock=5)
    o = paid(items_of((a, 1), (b, 1)), u, use_points=3000)
    expect_eq((o["totalPrice"], o["cardAmount"]), (4000, 1000), "fixture")
    expect_eq(balance(u), 0, "balance after pay")
    expect_status(refund(o["id"], [(a, 1)]), 200, "refund a")
    expect_eq([c["amount"] for c in refund_calls(o["id"])], [1000], "card refunded first", "pg")
    expect_eq(balance(u), 1000, "rest to points", "atomicity")
    expect_status(refund(o["id"], [(b, 1)]), 200, "refund b")
    expect_eq(len(refund_calls(o["id"])), 1, "no PG call when card refund is 0", "pg")
    expect_eq(balance(u), 3000, "all points back", "atomicity")
    expect_eq(get_order(o["id"])["status"], "REFUNDED", "status")


def p4_points_only_no_pg():
    """P4.6 P4.7 P3.2"""
    u = user_with(5000)
    pid = new_product(price=1000, stock=5)
    o = paid(items_of((pid, 3)), u, use_points=3000)
    r = refund(o["id"], [(pid, 2)])
    expect_status(r, 200, "refund points-only order")
    expect_eq(r.body["refundedAmount"], 2000, "refundedAmount")
    expect_eq(refund_calls(o["id"]), [], "PG not called", "pg")
    expect_eq(balance(u), 4000, "points back", "atomicity")


def p4_validation_400():
    """P4.2"""
    pid = new_product(stock=10)
    o = paid(items_of((pid, 2)))
    oid = o["id"]
    cases = [
        ("no key", call("POST", f"/api/orders/{oid}/refunds", {"items": items_of((pid, 1))})),
        ("long key", refund(oid, [(pid, 1)], key="k" * 65)),
        ("empty items", call("POST", f"/api/orders/{oid}/refunds", {"items": []},
                             headers={"Idempotency-Key": new_key()})),
        ("quantity 0", refund(oid, [(pid, 0)])),
        ("duplicate product", refund(oid, [(pid, 1), (pid, 1)])),
    ]
    for what, r in cases:
        expect_problem(r, 400, what, "VALIDATION_ERROR")
    g = get_order(oid)
    expect_eq((g["status"], refunded_qty(g, pid)), ("PAID", 0), "unchanged", "atomicity")


def p4_not_found_404():
    """P4.2"""
    expect_problem(refund(rg.missing_id("order"), [(new_product(), 1)]), 404, "missing order", "ORDER_NOT_FOUND")


def p4_invalid_state_409():
    """P4.3 P5.2"""
    pid = new_product(stock=50)
    pending = order(items_of((pid, 1)))
    expect_problem(refund(pending["id"], [(pid, 1)]), 409, "PENDING_PAYMENT", "INVALID_STATE")
    expect_problem(refund(pending["id"], [(pid, 99)]), 409, "state checked before quantity", "INVALID_STATE")
    cancelled = order(items_of((pid, 1)))
    rg.action(cancelled["id"], "cancel")
    expect_problem(refund(cancelled["id"], [(pid, 1)]), 409, "CANCELLED", "INVALID_STATE")
    shipped = paid(items_of((pid, 1)))
    expect_status(rg.action(shipped["id"], "ship"), 200, "ship")
    expect_problem(refund(shipped["id"], [(pid, 1)]), 409, "SHIPPED", "INVALID_STATE")
    full = paid(items_of((pid, 1)))
    expect_status(refund(full["id"], [(pid, 1)]), 200, "full refund")
    expect_problem(refund(full["id"], [(pid, 1)]), 409, "REFUNDED", "INVALID_STATE")


def p4_quantity_exceeded_409():
    """P4.4"""
    a, b, other = new_product(stock=10), new_product(stock=10), new_product(stock=10)
    o = paid(items_of((a, 2), (b, 1)))
    expect_problem(refund(o["id"], [(a, 3)]), 409, "more than ordered", "REFUND_QUANTITY_EXCEEDED")
    expect_problem(refund(o["id"], [(other, 1)]), 409, "product not in order", "REFUND_QUANTITY_EXCEEDED")
    expect_status(refund(o["id"], [(a, 1)]), 200, "refund 1")
    expect_problem(refund(o["id"], [(a, 2)]), 409, "more than remaining", "REFUND_QUANTITY_EXCEEDED")
    g = get_order(o["id"])
    expect_eq((refunded_qty(g, a), refunded_qty(g, b)), (1, 0), "only the valid refund applied", "atomicity")
    rg.check_product(product(a), "product a", stock=9)


def p4_pg_failure_503():
    """P4.8"""
    u = user_with(1000)
    pid, code = new_product(price=2000, stock=10), new_coupon(value=100)
    o = paid(items_of((pid, 2)), u, code, use_points=1000, token="tok_refund_fail")
    expect_problem(refund(o["id"], [(pid, 1)]), 503, "PG refund 500", "PAYMENT_GATEWAY_UNAVAILABLE")
    g = get_order(o["id"])
    expect_eq((g["status"], g["refundedAmount"], refunded_qty(g, pid)), ("PAID", 0, 0), "order unchanged",
              "atomicity")
    rg.check_product(product(pid), "product", stock=8)
    expect_eq(balance(u), 0, "points unchanged", "atomicity")
    expect_eq(coupon(code)["usedCount"], 1, "coupon unchanged", "atomicity")


def p4_pg_timeout_then_retry():
    """P4.8 P4.10 R4.4 P4.7"""
    pid = new_product(price=1000, stock=10)
    o = paid(items_of((pid, 2)), token="tok_refund_slow")
    key = new_key()
    r = refund(o["id"], [(pid, 1)], key)
    expect_status(r, 503, "PG refund slow")
    if r.elapsed > 4.5:
        raise Fail("timing", f"responded after {r.elapsed:.1f}s (PG timeout must be 2s)")
    g = get_order(o["id"])
    expect_eq((g["status"], refunded_qty(g, pid)), ("PAID", 0), "unchanged after timeout", "atomicity")
    time.sleep(4.0)
    r = refund(o["id"], [(pid, 1)], key)
    expect_status(r, 200, "retry with same key")
    expect_eq((r.body["refundedAmount"], refunded_qty(r.body, pid)), (1000, 1), "after retry")
    expect_eq({c["idempotencyKey"] for c in refund_calls(o["id"])}, {key}, "same PG key across retries", "pg")
    expect_eq(applied_refund_sum(o["id"]), 1000, "PG refunded once", "pg")


def p4_idempotent_replay():
    """P4.10 R4.2 R4.3 R4.1"""
    pid = new_product(stock=10)
    pay_key = new_key()
    o = order(items_of((pid, 3)))
    expect_status(rg.pay(o["id"], key=pay_key), 200, "pay")
    key = new_key()
    first = refund(o["id"], [(pid, 1)], key)
    expect_status(first, 200, "first refund")
    again = refund(o["id"], [(pid, 1)], key)
    expect_status(again, 200, "replay")
    expect_eq(again.body, first.body, "replayed body", "idempotency")
    expect_eq(refunded_qty(get_order(o["id"]), pid), 1, "processed once", "idempotency")
    expect_eq(len(refund_calls(o["id"])), 1, "PG refund once", "pg")
    expect_problem(refund(o["id"], [(pid, 2)], key), 422, "different body", "IDEMPOTENCY_KEY_MISMATCH")
    expect_status(refund(o["id"], [(pid, 1)], pay_key), 200, "pay key string is a separate key space")


# ---------- P5 기존 동작 변경 ----------

def p5_cancel_partially_refunded():
    """P5.1 P4.5 P4.9"""
    a, b = new_product(price=1000, stock=10), new_product(price=3000, stock=10)
    code = new_coupon(value=1000)
    o = paid(items_of((a, 2), (b, 1)), coupon_code=code)
    expect_status(refund(o["id"], [(a, 1)]), 200, "partial refund")
    r = rg.action(o["id"], "cancel")
    expect_status(r, 200, "cancel")
    check_t3_order(r.body, "cancel body")
    expect_eq((r.body["status"], r.body["refundedAmount"], refunded_qty(r.body, a), refunded_qty(r.body, b)),
              ("REFUNDED", 4000, 2, 1), "after cancel")
    last = refund_calls(o["id"])[-1]
    expect_eq((last["amount"], last["idempotencyKey"]), (3200, f"cancel-{o['id']}"), "cancel PG refund", "pg")
    rg.check_product(product(a), "product a", stock=10)
    rg.check_product(product(b), "product b", stock=10)
    expect_eq(coupon(code)["usedCount"], 0, "coupon restored", "atomicity")


def p5_cancel_paid_with_points():
    """P5.1 P4.6"""
    u = user_with(1000)
    o = paid(items_of((new_product(price=2500), 1)), u, use_points=1000)
    r = rg.action(o["id"], "cancel")
    expect_status(r, 200, "cancel")
    expect_eq((r.body.get("status"), r.body.get("refundedAmount")), ("REFUNDED", 2500), "after cancel")
    expect_eq([(c["amount"], c["idempotencyKey"]) for c in refund_calls(o["id"])], [(1500, f"cancel-{o['id']}")],
              "PG refund card part", "pg")
    expect_eq(balance(u), 1000, "points back", "atomicity")


def p5_ship_from_partial():
    """P5.2"""
    a, b = new_product(stock=10), new_product(stock=10)
    o = paid(items_of((a, 1), (b, 1)))
    expect_status(refund(o["id"], [(a, 1)]), 200, "partial refund")
    r = rg.action(o["id"], "ship")
    expect_status(r, 200, "ship PARTIALLY_REFUNDED")
    expect_eq(r.body.get("status"), "SHIPPED", "status")
    expect_problem(refund(o["id"], [(b, 1)]), 409, "refund after ship", "INVALID_STATE")
    expect_problem(rg.action(o["id"], "cancel"), 409, "cancel after ship", "INVALID_STATE")
    expect_status(rg.action(o["id"], "deliver"), 200, "deliver")


def p5_list_filter_partial():
    """P5.3"""
    u = new_user()
    a, b = new_product(stock=10), new_product(stock=10)
    o = paid(items_of((a, 1), (b, 1)), u)
    order(items_of((a, 1)), u)
    expect_status(refund(o["id"], [(a, 1)]), 200, "partial refund")
    r = rg.list_orders(userId=u, status="PARTIALLY_REFUNDED")
    expect_status(r, 200, "list PARTIALLY_REFUNDED")
    expect_eq([str(x["id"]) for x in r.body["content"]], [str(o["id"])], "filtered ids")


# ---------- P6 동시성 ----------

def p6_points_race():
    """P6.1"""
    for rep in range(rg.REPS):
        u = user_with(1000)
        pid = new_product(price=1000, stock=50)
        rs = concurrently(lambda i: create(items_of((pid, 1)), u, use_points=600), 5)
        ok = sum(r.status == 201 for r in rs)
        if any(r.status >= 500 for r in rs):
            raise Fail("concurrency", f"rep {rep}: 5xx {[r.status for r in rs]}")
        expect_eq(ok, 1, f"rep {rep}: successes", "concurrency")
        expect_eq(balance(u), 400, f"rep {rep}: balance", "concurrency")


def p6_refund_race():
    """P6.2"""
    for rep in range(rg.REPS):
        a, b = new_product(price=1000, stock=10), new_product(price=500, stock=10)
        o = paid(items_of((a, 3), (b, 1)))
        rs = concurrently(lambda i: refund(o["id"], [(a, 1)]), 5)
        statuses = sorted(r.status for r in rs)
        expect_eq(statuses, [200, 200, 200, 409, 409], f"rep {rep}: statuses", "concurrency")
        if any(r.status == 409 and r.code != "REFUND_QUANTITY_EXCEEDED" for r in rs):
            raise Fail("code", f"rep {rep}: 409 codes {[r.code for r in rs]}")
        g = get_order(o["id"])
        expect_eq(refunded_qty(g, a), 3, f"rep {rep}: refundedQuantity", "concurrency")
        expect_eq(applied_refund_sum(o["id"]), g["refundedAmount"], f"rep {rep}: PG sum = refundedAmount",
                  "concurrency")
        rg.check_product(product(a), f"rep {rep}: product", stock=10)


def p6_cancel_vs_refund():
    """P6.3"""
    for rep in range(rg.REPS):
        a, b = new_product(price=1000, stock=10), new_product(price=700, stock=10)
        o = paid(items_of((a, 2), (b, 1)))
        rs = concurrently(lambda i: rg.action(o["id"], "cancel") if i == 0 else refund(o["id"], [(a, 1)]), 2)
        if any(r.status >= 500 for r in rs):
            raise Fail("concurrency", f"rep {rep}: 5xx {[r.status for r in rs]}")
        g = get_order(o["id"])
        expect_eq((g["status"], g["refundedAmount"], refunded_qty(g, a), refunded_qty(g, b)),
                  ("REFUNDED", g["totalPrice"], 2, 1), f"rep {rep}: final", "concurrency")
        expect_eq(applied_refund_sum(o["id"]), g["cardAmount"], f"rep {rep}: PG sum", "concurrency")
        rg.check_product(product(a), f"rep {rep}: product a", stock=10)
        rg.check_product(product(b), f"rep {rep}: product b", stock=10)


# ---------- 명세 해석 (주 지표 밖) ----------

def i_grant_race():
    """해석: 포인트 적립 동시 호출에도 잔액이 정확하다 (동시 적립은 명세에 없음)"""
    u = new_user()
    rs = concurrently(lambda i: grant(u, 100), 20)
    if any(r.status != 200 for r in rs):
        raise Fail("concurrency", f"statuses {sorted(r.status for r in rs)}")
    expect_eq(balance(u), 2000, "balance", "concurrency")


# (req, fn, layer, ttl)
CASES = [
    ("P1", p1_grant_and_get, "spec", False), ("P1", p1_grant_invalid_400, "spec", False),
    ("P1", p1_balance_excludes_used, "spec", False),
    ("P2", p2_order_fields, "spec", False), ("P2", p2_use_points_invalid_400, "spec", False),
    ("P2", p2_exceed_total_409, "spec", False), ("P2", p2_insufficient_409, "spec", False),
    ("P2", p2_precedence, "spec", False), ("P2", p2_restore_on_cancel, "spec", False),
    ("P2", p2_restore_on_decline, "spec", False), ("P2", p2_restore_on_expiry, "spec", True),
    ("P2", p2_idempotency_points, "spec", False),
    ("P3", p3_pg_amount_is_card, "spec", False), ("P3", p3_zero_card_no_pg, "spec", False),
    ("P3", p3_pg_error_keeps_points, "spec", False),
    ("P4", p4_partial_refund_basic, "spec", False), ("P4", p4_full_refund_sums_total, "spec", False),
    ("P4", p4_card_first_then_points, "spec", False), ("P4", p4_points_only_no_pg, "spec", False),
    ("P4", p4_validation_400, "spec", False), ("P4", p4_not_found_404, "spec", False),
    ("P4", p4_invalid_state_409, "spec", False), ("P4", p4_quantity_exceeded_409, "spec", False),
    ("P4", p4_pg_failure_503, "spec", False), ("P4", p4_pg_timeout_then_retry, "spec", False),
    ("P4", p4_idempotent_replay, "spec", False),
    ("P5", p5_cancel_partially_refunded, "spec", False), ("P5", p5_cancel_paid_with_points, "spec", False),
    ("P5", p5_ship_from_partial, "spec", False), ("P5", p5_list_filter_partial, "spec", False),
    ("P6", p6_points_race, "spec", False), ("P6", p6_refund_race, "spec", False),
    ("P6", p6_cancel_vs_refund, "spec", False),
    ("I", i_grant_race, "interp", False),
]
REQUIREMENTS = ["P1", "P2", "P3", "P4", "P5", "P6"]
# 2차 회귀 케이스 중 이번 변경과 충돌해 빼는 것 (함수명: 사유)
REGRESSION_EXCLUDE = {}
