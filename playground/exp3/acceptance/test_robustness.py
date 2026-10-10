#!/usr/bin/env python3
"""3차 실험 숨긴 테스트 — 계약(K1~K10) + 견고성(S1~S25, PRD §5).

- 계약: feature-short.md에 적힌 경로·필드·상태만 검증한다.
- 견고성: 명세가 정하지 않은 상식적 요구. 상태코드를 특정하지 않는다.
    거절 = 4xx (5xx 아님) · 불변 = 관련 상품 stock/reserved, 쿠폰 usedCount, 주문 status가 요청 전과 같음
  명세가 비운 판단은 일관된 결과면 모두 인정한다(예: 중복 항목은 거절 또는 정확히 합산).
- S 항목 하나 = 케이스 하나. 하위 검사 하나라도 실패하면 그 항목은 실패.
- 단계: main(S12·S13 제외, 앱 TTL 길게) / ttl(S12·S13, 앱 TTL=PT3S로 다시 기동)
- 표준 라이브러리만 사용. PG는 pg_stub.py(--pg-url).

사용법: test_robustness.py --base-url http://localhost:18080 --pg-url http://localhost:18090
                          [--phase main|ttl|all] [--ttl-seconds 600] [--out result.json] [--only K1,S3] [--repeat 5]
"""
import argparse
import json
import random
import re
import string
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from urllib import error, request
from urllib.parse import urlencode, urljoin

BASE = ""
PG = ""
TTL_SECONDS = 600.0
REPEAT = 5
EXPIRE_WINDOW = 10.0  # S13: expiresAt 뒤 이 시간 안에 만료·복원이 보여야 한다

CATEGORIES = {
    "입력 검증": ["S1", "S2", "S3", "S4", "S5"],
    "재고·쿠폰 정합성": ["S6", "S7", "S8", "S9", "S10"],
    "상태 전이": ["S11", "S12", "S13"],
    "멱등성": ["S14", "S15", "S16"],
    "외부 PG 장애": ["S17", "S18", "S19"],
    "동시성": ["S20", "S21", "S22", "S23"],
    "목록": ["S24", "S25"],
}
TTL_PHASE = {"S12", "S13"}
GATEWAY_ERRORS = {502, 503, 504}  # PG 장애를 의도적으로 알리는 응답. 500은 처리하지 못한 오류로 본다


class Fail(Exception):
    def __init__(self, category, message):
        super().__init__(message)
        self.category = category


class Resp:
    def __init__(self, status, headers, raw):
        self.status = status
        self.headers = {k.lower(): v for k, v in headers.items()}
        self.raw = raw
        try:
            self.body = json.loads(raw) if raw else None
        except ValueError:
            self.body = None

    @property
    def ok(self):
        return 200 <= self.status < 300

    @property
    def rejected(self):
        return 400 <= self.status < 500

    def brief(self):
        return f"{self.status} {self.raw[:160]}"


def call(method, path, body=None, raw_body=None, headers=None, url=None, timeout=30):
    h = {"Accept": "application/json, application/problem+json"}
    data = None
    if raw_body is not None:
        data = raw_body.encode()
        h["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = request.Request(url or BASE + path, data=data, method=method, headers=h)
    try:
        with request.urlopen(req, timeout=timeout) as r:
            return Resp(r.status, dict(r.headers), r.read().decode())
    except error.HTTPError as e:
        return Resp(e.code, dict(e.headers), e.read().decode())


# ---------- 판정 도우미 ----------

def num(v):
    """숫자 비교용. 1500 · 1500.0 · "1500.00"을 같게 본다."""
    if isinstance(v, bool) or v is None:
        return None
    try:
        return Decimal(str(v))
    except InvalidOperation:
        return None


def check(cond, category, message):
    if not cond:
        raise Fail(category, message)


def expect_status(resp, status, what):
    check(resp.status == status, "status", f"{what}: expected {status}, got {resp.brief()}")


def expect_ok(resp, what):
    check(resp.ok, "status", f"{what}: expected 2xx, got {resp.brief()}")


def expect_rejected(resp, what):
    check(resp.rejected, "reject", f"{what}: expected 4xx, got {resp.brief()}")


def expect_no_500(resp, what):
    check(resp.status < 500 or resp.status in GATEWAY_ERRORS, "server-error", f"{what}: got {resp.brief()}")


def expect_fields(obj, fields, what):
    check(isinstance(obj, dict), "field", f"{what}: body is not an object: {str(obj)[:160]}")
    missing = [f for f in fields if obj.get(f) is None]
    check(not missing, "field", f"{what}: missing fields {missing}")


def expect_num(actual, expected, what, category="field"):
    check(num(actual) == num(expected), category, f"{what}: expected {expected}, got {actual!r}")


def expect_problem(resp, what):
    check(resp.headers.get("content-type", "").startswith("application/problem+json"), "format",
          f"{what}: content-type {resp.headers.get('content-type')!r}")
    check(isinstance(resp.body, dict) and resp.body.get("status") == resp.status, "format",
          f"{what}: not a Problem Details body: {resp.raw[:160]}")


def parse_iso(value, what):
    check(isinstance(value, str), "field", f"{what}: expected ISO-8601 string, got {value!r}")
    s = re.sub(r"(\.\d{6})\d+", r"\1", value.replace("Z", "+00:00"))
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        raise Fail("field", f"{what}: not ISO-8601: {value!r}")
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


# ---------- 픽스처 ----------

def hexid(n=8):
    return uuid.uuid4().hex[:n]


def new_user():
    return str(random.randint(10 ** 8, 10 ** 9 - 1))  # 숫자 문자열: 문자열·정수 어느 타입으로 받아도 된다


def new_key():
    return str(uuid.uuid4())


def new_code():
    return "C" + "".join(random.choices(string.ascii_uppercase + string.digits, k=11))


def now():
    return datetime.now(timezone.utc)


def fixture(resp, what):
    if not resp.ok or not isinstance(resp.body, dict):
        raise Fail("fixture", f"fixture {what} failed: {resp.brief()}")
    return resp.body


def new_product(price=1000, stock=100):
    return fixture(call("POST", "/api/products", {"name": f"p-{hexid()}", "price": price, "stock": stock}),
                   "product create")["id"]


def product(pid):
    return fixture(call("GET", f"/api/products/{pid}"), "product get")


# 명세가 validFrom/validUntil 형식을 정하지 않았다. 앱이 받는 형식을 처음 한 번 찾아 쓴다.
_DATE_FORMATS = [
    lambda d: d.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    lambda d: d.astimezone(timezone.utc).replace(tzinfo=None).isoformat(timespec="seconds"),
    lambda d: d.astimezone(timezone.utc).date().isoformat(),
]
_FMT = []


def coupon_body(**kw):
    body = {"code": new_code(), "type": "FIXED", "value": 1000, "minOrderAmount": 0, "maxDiscountAmount": 100000,
            "totalQuantity": 100, "validFrom": now() - timedelta(days=2), "validUntil": now() + timedelta(days=2)}
    body.update(kw)
    return body


def post_coupon(body):
    def render(fmt):
        return {k: fmt(v) if isinstance(v, datetime) else v for k, v in body.items()}

    if not _FMT:
        probe = coupon_body()
        for fmt in _DATE_FORMATS:
            r = call("POST", "/api/coupons", {k: fmt(v) if isinstance(v, datetime) else v for k, v in probe.items()})
            if r.ok:
                _FMT.append(fmt)
                break
        else:
            raise Fail("fixture", f"coupon create failed in every date format: {r.brief()}")
    return call("POST", "/api/coupons", render(_FMT[0]))


def new_coupon(**kw):
    return fixture(post_coupon(coupon_body(**kw)), "coupon create")["code"]


def coupon(code):
    return fixture(call("GET", f"/api/coupons/{code}"), "coupon get")


def items_of(*pairs):
    return [{"productId": p, "quantity": q} for p, q in pairs]


def create_order(items, user=None, coupon_code=None, key=None, raw_body=None):
    body = {"items": items}
    if coupon_code is not None:
        body["couponCode"] = coupon_code
    h = {"X-User-Id": user or new_user(), "Idempotency-Key": key or new_key()}
    return call("POST", "/api/orders", None if raw_body else body, raw_body=raw_body, headers=h)


def new_order(items, user=None, coupon_code=None):
    return fixture(create_order(items, user, coupon_code), "order create")


def get_order(oid):
    return fixture(call("GET", f"/api/orders/{oid}"), "order get")


def pay(oid, token="tok_ok", key=None, timeout=30):
    return call("POST", f"/api/orders/{oid}/pay", {"cardToken": token},
                headers={"Idempotency-Key": key or new_key()}, timeout=timeout)


def paid_order(items, user=None, coupon_code=None):
    o = new_order(items, user, coupon_code)
    r = pay(o["id"])
    fixture(r, "pay")
    check(get_order(o["id"])["status"] == "PAID", "fixture", "fixture pay did not reach PAID")
    return o


def action(oid, name):
    return call("POST", f"/api/orders/{oid}/{name}")


def pg_calls(oid, kind="payment"):
    r = call("GET", None, url=f"{PG}/__admin/calls?orderId={oid}")
    return [c for c in (r.body or []) if c.get("kind") == kind]


def charges(oid):
    """실제 청구 횟수 — 멱등 키 재전송으로 스텁이 저장된 결과를 돌려준 호출은 빼고 센다."""
    return [c for c in pg_calls(oid) if c.get("paymentId") and not c.get("replayed")]


def list_orders(**params):
    return call("GET", "/api/orders?" + urlencode({k: v for k, v in params.items() if v is not None}))


def user_orders(user):
    r = list_orders(userId=user, size=100)
    return fixture(r, "list").get("content") or []


def state(pids=(), code=None, oid=None):
    s = {}
    for p in pids:
        b = product(p)
        s[f"product {p}"] = (num(b.get("stock")), num(b.get("reserved")))
    if code:
        s[f"coupon {code}"] = num(coupon(code).get("usedCount"))
    if oid is not None:
        s[f"order {oid}"] = get_order(oid).get("status")
    return s


def expect_unchanged(before, pids=(), code=None, oid=None, what=""):
    after = state(pids, code, oid)
    diff = {k: (before[k], after[k]) for k in before if before[k] != after[k]}
    check(not diff, "invariant", f"{what}: changed {diff}")


def reserved(pid):
    return num(product(pid).get("reserved"))


def stock(pid):
    return num(product(pid).get("stock"))


def available(pid):
    """재고 차감 시점(결제·출고)은 명세가 정하지 않았다 — 결제 뒤에는 가용 재고만 본다."""
    b = product(pid)
    return num(b.get("stock")) - num(b.get("reserved"))


def concurrently(fn, n):
    barrier = threading.Barrier(n)

    def run(i):
        barrier.wait()
        try:
            return fn(i)
        except Exception as e:  # 연결 끊김·타임아웃도 결과로 센다
            return e

    with ThreadPoolExecutor(max_workers=n) as ex:
        return list(ex.map(run, range(n)))


def no_crash(results, what):
    bad = [r if isinstance(r, Exception) else r.brief() for r in results
           if isinstance(r, Exception) or r.status >= 500]
    check(not bad, "server-error", f"{what}: {len(bad)} 5xx/errors, e.g. {str(bad[0])[:160] if bad else ''}")


_MISSING = {}


def missing_id():
    if "id" not in _MISSING:
        _MISSING["id"] = new_product()
    sample = _MISSING["id"]
    if isinstance(sample, int) or (isinstance(sample, str) and sample.isdigit()):
        return 987654321
    return str(uuid.uuid4())


# ---------- 계약 K1~K10 ----------

def k1_product():
    """상품 등록·조회"""
    name = f"p-{hexid()}"
    r = call("POST", "/api/products", {"name": name, "price": 1500, "stock": 7})
    expect_status(r, 201, "create product")
    check(r.headers.get("location"), "field", "Location header missing")
    expect_fields(r.body, ("id", "name", "price", "stock", "reserved", "available"), "create body")
    g = call("GET", None, url=urljoin(BASE + "/", r.headers["location"]))
    expect_status(g, 200, "GET Location")
    b = g.body
    expect_fields(b, ("id", "name", "price", "stock", "reserved", "available"), "get body")
    check(b["name"] == name, "field", f"name {b['name']!r}")
    for f, v in (("price", 1500), ("stock", 7), ("reserved", 0), ("available", 7)):
        expect_num(b[f], v, f"product.{f}")


def k2_coupon():
    """쿠폰 등록·조회"""
    body = coupon_body(type="RATE", value=10, minOrderAmount=5000, maxDiscountAmount=3000, totalQuantity=50)
    r = post_coupon(body)
    expect_status(r, 201, "create coupon")
    check(r.headers.get("location"), "field", "Location header missing")
    g = call("GET", None, url=urljoin(BASE + "/", r.headers["location"]))
    expect_status(g, 200, "GET Location")
    expect_fields(g.body, ("code", "type", "value", "minOrderAmount", "maxDiscountAmount", "totalQuantity",
                           "usedCount", "validFrom", "validUntil"), "coupon body")
    check(g.body["code"] == body["code"] and g.body["type"] == "RATE", "field", f"coupon {g.raw[:160]}")
    for f in ("value", "minOrderAmount", "maxDiscountAmount", "totalQuantity"):
        expect_num(g.body[f], body[f], f"coupon.{f}")
    expect_num(g.body["usedCount"], 0, "coupon.usedCount")


def k3_order_create():
    """주문 생성 — PENDING_PAYMENT, 금액, 예약, 만료 시각"""
    p, q = new_product(price=1200, stock=10), new_product(price=800, stock=10)
    user = new_user()
    r = create_order(items_of((p, 2), (q, 1)), user)
    expect_status(r, 201, "create order")
    check(r.headers.get("location"), "field", "Location header missing")
    g = call("GET", None, url=urljoin(BASE + "/", r.headers["location"]))
    expect_status(g, 200, "GET Location")
    o = g.body
    expect_fields(o, ("id", "userId", "status", "items", "subtotal", "discount", "totalPrice", "createdAt",
                      "expiresAt"), "order")
    check(o["status"] == "PENDING_PAYMENT", "field", f"status {o['status']}")
    check(str(o["userId"]) == user, "field", f"userId {o['userId']!r}")
    units = {str(i.get("productId")): num(i.get("unitPrice")) for i in o["items"]}
    check(units == {str(p): Decimal(1200), str(q): Decimal(800)}, "field", f"items {o['items']}")
    expect_num(o["subtotal"], 3200, "subtotal")
    expect_num(o["discount"], 0, "discount")
    expect_num(o["totalPrice"], 3200, "totalPrice")
    pb = product(p)
    for f, v in (("stock", 10), ("reserved", 2), ("available", 8)):
        expect_num(pb[f], v, f"product.{f}", "invariant")
    ttl = (parse_iso(o["expiresAt"], "expiresAt") - parse_iso(o["createdAt"], "createdAt")).total_seconds()
    check(abs(ttl - TTL_SECONDS) <= 5, "field", f"expiresAt - createdAt = {ttl}s, TTL {TTL_SECONDS}s")


def k4_discount():
    """쿠폰 할인 — 정액, 정률, 최대 할인액"""
    p = new_product(price=5000)
    cases = [(dict(type="FIXED", value=1000), 1, 1000),
             (dict(type="RATE", value=10, maxDiscountAmount=5000), 4, 2000),
             (dict(type="RATE", value=50, maxDiscountAmount=3000), 4, 3000)]
    for spec, qty, discount in cases:
        code = new_coupon(**spec)
        o = new_order(items_of((p, qty)), coupon_code=code)
        check(o.get("couponCode") == code, "field", f"couponCode {o.get('couponCode')!r}")
        expect_num(o["discount"], discount, f"{spec} discount")
        expect_num(o["totalPrice"], 5000 * qty - discount, f"{spec} totalPrice")


def k5_pay():
    """결제 승인 — PAID, paidAt, 가용 재고 유지, PG 호출"""
    p = new_product(price=2000, stock=10)
    o = new_order(items_of((p, 3)))
    r = pay(o["id"])
    expect_status(r, 200, "pay")
    g = get_order(o["id"])
    check(g["status"] == "PAID" and g.get("paidAt"), "field", f"after pay {g.get('status')} paidAt={g.get('paidAt')}")
    expect_num(available(p), 7, "available after pay", "invariant")
    calls = pg_calls(o["id"])
    check(len(calls) == 1, "pg", f"PG payment calls {len(calls)}")
    check(calls[0].get("idempotencyKey") and num(calls[0].get("amount")) == 6000 and calls[0].get("cardToken") == "tok_ok",
          "pg", f"PG request {calls[0]}")


def k6_cancel_pending():
    """미결제 취소 — CANCELLED"""
    p = new_product(stock=5)
    o = new_order(items_of((p, 2)))
    expect_ok(action(o["id"], "cancel"), "cancel")
    check(get_order(o["id"])["status"] == "CANCELLED", "field", "status after cancel")
    expect_num(product(p)["reserved"], 0, "reserved after cancel", "invariant")


def k7_cancel_paid():
    """결제 후 취소 — PG 환불, REFUNDED"""
    o = paid_order(items_of((new_product(), 1)))
    expect_ok(action(o["id"], "cancel"), "cancel paid")
    check(get_order(o["id"])["status"] == "REFUNDED", "field", "status after refund")
    check(len(pg_calls(o["id"], "refund")) == 1, "pg", "PG refund not called once")


def k8_ship_deliver():
    """배송 — SHIPPED, DELIVERED"""
    o = paid_order(items_of((new_product(), 1)))
    expect_ok(action(o["id"], "ship"), "ship")
    check(get_order(o["id"])["status"] == "SHIPPED", "field", "status after ship")
    expect_ok(action(o["id"], "deliver"), "deliver")
    check(get_order(o["id"])["status"] == "DELIVERED", "field", "status after deliver")


def k9_list():
    """목록 — userId·status 필터, size·nextCursor 페이지"""
    user, p = new_user(), new_product()
    ids = [str(new_order(items_of((p, 1)), user)["id"]) for _ in range(5)]
    action(ids[0], "cancel")
    new_order(items_of((p, 1)))  # 다른 사용자
    seen, cursor = [], None
    for _ in range(10):
        r = list_orders(userId=user, size=2, cursor=cursor)
        expect_status(r, 200, "list")
        check(isinstance(r.body, dict) and isinstance(r.body.get("content"), list) and "nextCursor" in r.body,
              "field", f"list shape {r.raw[:160]}")
        check(len(r.body["content"]) <= 2, "field", "page larger than size")
        seen += [str(o["id"]) for o in r.body["content"]]
        cursor = r.body["nextCursor"]
        if not cursor:
            break
    check(sorted(seen) == sorted(ids), "field", f"paged ids {seen} != {ids}")
    r = list_orders(userId=user, status="CANCELLED", size=100)
    expect_status(r, 200, "status filter")
    check([str(o["id"]) for o in r.body["content"]] == [ids[0]], "field", f"status filter {r.raw[:160]}")


def k10_problem_details():
    """없는 자원 — 404 Problem Details"""
    for path in (f"/api/products/{missing_id()}", f"/api/orders/{missing_id()}", f"/api/coupons/{new_code()}"):
        r = call("GET", path)
        expect_status(r, 404, path)
        expect_problem(r, path)


# ---------- 견고성: 입력 검증 ----------

def s1_product_validation():
    """음수·0 가격, 음수 재고, 빈 이름 → 거절"""
    new_product()  # 엔드포인트가 없어서 나는 4xx(404 등)를 거절로 세지 않게, 정상 요청이 먼저 통해야 한다
    for body in ({"name": "x", "price": -1, "stock": 1}, {"name": "x", "price": 0, "stock": 1},
                 {"name": "x", "price": 100, "stock": -1}, {"name": "", "price": 100, "stock": 1},
                 {"name": "   ", "price": 100, "stock": 1}):
        expect_rejected(call("POST", "/api/products", body), f"product {body}")


def s2_order_items_validation():
    """수량 0·음수, 빈 items → 거절 + 불변 / 같은 상품 중복 → 거절 또는 정확히 합산"""
    p, user = new_product(stock=10), new_user()
    before = state([p])
    for items in (items_of((p, 0)), items_of((p, -1)), []):
        expect_rejected(create_order(items, user), f"items {items}")
        expect_unchanged(before, [p], what=f"items {items}")
    check(not user_orders(user), "invariant", "rejected order was persisted")
    r = create_order(items_of((p, 1), (p, 2)), user)
    if r.rejected:
        expect_unchanged(before, [p], what="duplicate items rejected")
    else:
        expect_ok(r, "duplicate items")
        expect_num(reserved(p), 3, "duplicate items accepted: reserved", "invariant")
        expect_num(sum(num(i["quantity"]) for i in r.body["items"]), 3, "duplicate items: quantity sum", "invariant")


def s3_coupon_validation():
    """RATE value > 100, validFrom ≥ validUntil → 거절"""
    new_coupon()
    t = now()
    for kw, what in ((dict(type="RATE", value=101), "rate 101"),
                     (dict(validFrom=t + timedelta(days=1), validUntil=t + timedelta(days=1)), "from == until"),
                     (dict(validFrom=t + timedelta(days=2), validUntil=t - timedelta(days=2)), "from > until")):
        expect_rejected(post_coupon(coupon_body(**kw)), what)


def s4_malformed_json():
    """깨진 JSON, 타입 불일치 → 거절 + Problem Details"""
    new_order(items_of((new_product(), 1)))
    for path, raw in (("/api/products", '{"name": "x", "price": '),
                      ("/api/products", '{"name": "x", "price": "abc", "stock": 1}'),
                      ("/api/orders", '{"items": [{"productId": ')):
        if path == "/api/orders":
            r = create_order(None, raw_body=raw)
        else:
            r = call("POST", path, raw_body=raw)
        expect_rejected(r, f"{path} {raw}")
        expect_problem(r, f"{path} {raw}")


def s5_amount_overflow():
    """큰 금액 → 5xx 아님, 금액이 음수·잘림 아님 (거절도 인정)"""
    new_order(items_of((new_product(), 1)))
    pids = []
    for _ in range(3):
        r = call("POST", "/api/products", {"name": f"p-{hexid()}", "price": 10_000_000, "stock": 5000})
        if r.rejected:
            return  # 가격 상한을 두는 것도 오버플로 방어로 인정
        pids.append(fixture(r, "big product")["id"])
    before = state(pids)
    r = create_order(items_of(*[(p, 1000) for p in pids]))
    if r.rejected:
        expect_unchanged(before, pids, what="big order rejected")
        return
    expect_ok(r, "big order")
    expect_num(r.body["subtotal"], 30_000_000_000, "subtotal", "invariant")
    expect_num(r.body["totalPrice"], 30_000_000_000, "totalPrice", "invariant")


# ---------- 견고성: 재고·쿠폰 정합성 ----------

def s6_insufficient_stock_atomic():
    """available 초과 → 거절 + 불변, 부분 예약 없음"""
    a, b = new_product(stock=5), new_product(stock=1)
    before = state([a, b])
    expect_rejected(create_order(items_of((a, 2), (b, 3))), "one item short")
    expect_unchanged(before, [a, b], what="one item short")
    expect_rejected(create_order(items_of((a, 6))), "over available")
    expect_unchanged(before, [a, b], what="over available")
    new_order(items_of((a, 4)))
    expect_rejected(create_order(items_of((a, 2))), "over available after reservation")
    expect_num(reserved(a), 4, "reserved", "invariant")


def s7_discount_over_subtotal():
    """할인 > 소계 → totalPrice 음수 아님"""
    p = new_product(price=3000)
    code = new_coupon(type="FIXED", value=5000)
    r = create_order(items_of((p, 1)), coupon_code=code)
    if r.rejected:
        return
    expect_ok(r, "order")
    o = r.body
    check(num(o["totalPrice"]) >= 0, "invariant", f"totalPrice {o['totalPrice']}")
    check(num(o["discount"]) <= num(o["subtotal"]), "invariant", f"discount {o['discount']} > subtotal")
    expect_num(o["totalPrice"], num(o["subtotal"]) - num(o["discount"]), "total = subtotal - discount", "invariant")


def s8_coupon_conditions():
    """기간 밖·최소 금액 미달·소진·없는 쿠폰 → 거절 + 불변"""
    t, p = now(), new_product(price=5000, stock=100)
    expired = new_coupon(validFrom=t - timedelta(days=3), validUntil=t - timedelta(days=1))
    future = new_coupon(validFrom=t + timedelta(days=1), validUntil=t + timedelta(days=3))
    minimum = new_coupon(minOrderAmount=10000)
    for code, what in ((expired, "expired"), (future, "not yet valid"), (minimum, "below minimum")):
        before = state([p], code)
        expect_rejected(create_order(items_of((p, 1)), coupon_code=code), what)
        expect_unchanged(before, [p], code, what=what)
    before = state([p])
    expect_rejected(create_order(items_of((p, 1)), coupon_code=new_code()), "unknown coupon")
    expect_unchanged(before, [p], what="unknown coupon")
    one = new_coupon(totalQuantity=1)
    paid_order(items_of((p, 1)), coupon_code=one)
    before = state([p], one)
    expect_rejected(create_order(items_of((p, 1)), coupon_code=one), "exhausted")
    expect_unchanged(before, [p], one, what="exhausted")


def s9_coupon_once_per_user():
    """같은 사용자가 같은 쿠폰을 다시 사용 → 거절"""
    p, user, code = new_product(), new_user(), new_coupon(totalQuantity=10)
    paid_order(items_of((p, 1)), user, code)
    before = state([p], code)
    expect_rejected(create_order(items_of((p, 1)), user, code), "second use")
    expect_unchanged(before, [p], code, what="second use")


def s10_restore_on_cancel_refund():
    """취소·환불 → 예약·쿠폰 사용 복원"""
    p, code = new_product(stock=10), new_coupon(totalQuantity=5)
    base = state([p], code)
    o = new_order(items_of((p, 2)), coupon_code=code)
    expect_ok(action(o["id"], "cancel"), "cancel")
    expect_unchanged(base, [p], code, what="after cancel")
    o = paid_order(items_of((p, 3)), coupon_code=code)
    expect_ok(action(o["id"], "cancel"), "refund")
    check(get_order(o["id"])["status"] == "REFUNDED", "fixture", "refund did not reach REFUNDED")
    expect_unchanged(base, [p], code, what="after refund")


# ---------- 견고성: 상태 전이 ----------

def s11_invalid_transitions():
    """허용되지 않은 전이 → 거절 + 불변"""
    p = new_product(stock=100)

    def attempt(oid, name, what, **kw):
        before = state([p], oid=oid)
        payments = len(pg_calls(oid))
        r = pay(oid) if name == "pay" else action(oid, name)
        expect_rejected(r, what)
        expect_unchanged(before, [p], oid=oid, what=what)
        check(len(pg_calls(oid)) == payments, "pg", f"{what}: PG called")

    cancelled = new_order(items_of((p, 1)))
    action(cancelled["id"], "cancel")
    attempt(cancelled["id"], "pay", "pay CANCELLED")
    pending = new_order(items_of((p, 1)))
    attempt(pending["id"], "ship", "ship PENDING_PAYMENT")
    attempt(pending["id"], "deliver", "deliver PENDING_PAYMENT")
    paid = paid_order(items_of((p, 1)))
    attempt(paid["id"], "pay", "pay PAID again")
    attempt(paid["id"], "deliver", "deliver PAID")
    delivered = paid_order(items_of((p, 1)))
    action(delivered["id"], "ship")
    action(delivered["id"], "deliver")
    attempt(delivered["id"], "cancel", "cancel DELIVERED")
    refunded = paid_order(items_of((p, 1)))
    action(refunded["id"], "cancel")
    attempt(refunded["id"], "ship", "ship REFUNDED")


def s12_pay_after_expiry():
    """만료 시각이 지난 주문 결제 → 거절, PG 호출 없음"""
    o = new_order(items_of((new_product(), 1)))
    delay = (parse_iso(o["expiresAt"], "expiresAt") - now()).total_seconds() + 0.3
    time.sleep(max(delay, 0))
    r = pay(o["id"])
    expect_rejected(r, "pay after expiry")
    check(not pg_calls(o["id"]), "pg", "PG called for expired order")
    check(get_order(o["id"])["status"] != "PAID", "invariant", "expired order became PAID")


def s13_expiry_releases():
    """TTL 경과 후 수 초 안에 EXPIRED + 예약·쿠폰 복원 (주문을 조회하지 않아도 상품·쿠폰에 반영)"""
    p, code = new_product(stock=10), new_coupon(totalQuantity=5)
    base = state([p], code)
    o = new_order(items_of((p, 2)), coupon_code=code)
    deadline = parse_iso(o["expiresAt"], "expiresAt") + timedelta(seconds=EXPIRE_WINDOW)
    while True:
        if state([p], code) == base:
            break
        check(now() < deadline, "timing", f"reservation/coupon not released {EXPIRE_WINDOW}s after expiresAt: "
                                          f"{state([p], code)} vs {base}")
        time.sleep(0.5)
    check(get_order(o["id"])["status"] == "EXPIRED", "invariant", "status is not EXPIRED")


# ---------- 견고성: 멱등성 ----------

def s14_create_replay():
    """같은 키·같은 요청 재시도 → 주문 1건, 같은 응답"""
    p, user, key = new_product(stock=10), new_user(), new_key()
    first = create_order(items_of((p, 1)), user, key=key)
    expect_ok(first, "first")
    second = create_order(items_of((p, 1)), user, key=key)
    expect_ok(second, "replay")
    check(isinstance(second.body, dict) and str(second.body.get("id")) == str(first.body["id"]), "idempotency",
          f"replay returned another order: {second.raw[:160]}")
    expect_num(reserved(p), 1, "reserved after replay", "idempotency")
    check(len(user_orders(user)) == 1, "idempotency", "replay created another order")


def s15_create_key_mismatch():
    """같은 키·다른 본문 → 거절, 새 처리 없음"""
    p, user, key = new_product(stock=10), new_user(), new_key()
    expect_ok(create_order(items_of((p, 1)), user, key=key), "first")
    r = create_order(items_of((p, 2)), user, key=key)
    expect_rejected(r, "same key, different body")
    expect_num(reserved(p), 1, "reserved", "idempotency")
    check(len(user_orders(user)) == 1, "idempotency", "mismatched body created another order")


def s16_pay_replay():
    """같은 결제 재시도 → PG 결제 1회"""
    o, key = new_order(items_of((new_product(), 1))), new_key()
    expect_ok(pay(o["id"], key=key), "pay")
    r = pay(o["id"], key=key)
    expect_ok(r, "pay replay")
    check(get_order(o["id"])["status"] == "PAID", "invariant", "status after replay")
    check(len(pg_calls(o["id"])) == 1, "idempotency", f"PG payment calls {len(pg_calls(o['id']))}")


# ---------- 견고성: 외부 PG 장애 ----------

def _consistent_after_failure(oid, p, qty, before_reserved, what):
    """결제 실패 뒤 상태: 결제 대기 유지(예약 그대로) 또는 결제 실패(예약 해제). 어느 쪽이든 PAID는 아니다."""
    status = get_order(oid)["status"]
    res = reserved(p)
    if status == "PENDING_PAYMENT":
        check(res == before_reserved, "invariant", f"{what}: PENDING_PAYMENT but reserved {res} != {before_reserved}")
        expect_num(stock(p), 100, f"{what}: stock", "invariant")
    elif status == "PAYMENT_FAILED":
        check(res == before_reserved - qty, "invariant", f"{what}: PAYMENT_FAILED but reservation not released")
        expect_num(stock(p), 100, f"{what}: stock", "invariant")
    else:
        raise Fail("invariant", f"{what}: unexpected status {status}")
    return status


def s17_gateway_failure():
    """PG 5xx·연결 끊김·지연 → 처리하지 못한 500 아님 + 상태·재고 일관"""
    for token in ("tok_error", "tok_drop"):
        p = new_product(stock=100)
        o = new_order(items_of((p, 2)))
        r = pay(o["id"], token=token)
        expect_no_500(r, token)
        check(not r.ok or (isinstance(r.body, dict) and r.body.get("status") != "PAID"), "invariant",
              f"{token}: reported success {r.brief()}")
        if _consistent_after_failure(o["id"], p, 2, Decimal(2), token) == "PENDING_PAYMENT":
            expect_ok(pay(o["id"]), f"{token}: retry after gateway failure")
            check(get_order(o["id"])["status"] == "PAID", "invariant", f"{token}: retry did not reach PAID")
    p = new_product(stock=100)
    o = new_order(items_of((p, 2)))
    r = pay(o["id"], token="tok_slow", timeout=60)
    expect_no_500(r, "tok_slow")
    if get_order(o["id"])["status"] == "PAID":  # 끝까지 기다려 승인받는 것도 인정
        expect_num(available(p), 98, "tok_slow PAID: available", "invariant")
    else:
        _consistent_after_failure(o["id"], p, 2, Decimal(2), "tok_slow")


def s18_declined():
    """PG 거절 → PAID 아님 + 실패 처리면 예약·쿠폰 복원"""
    p, code = new_product(stock=100), new_coupon(totalQuantity=5)
    base = state([p], code)
    o = new_order(items_of((p, 2)), coupon_code=code)
    after_order = state([p], code)
    r = pay(o["id"], token="tok_decline")
    expect_no_500(r, "decline")
    check(r.status not in GATEWAY_ERRORS, "status", f"decline reported as gateway error {r.brief()}")
    status = get_order(o["id"])["status"]
    if status == "PAYMENT_FAILED":
        expect_unchanged(base, [p], code, what="declined: reservation/coupon")
    elif status == "PENDING_PAYMENT":  # 다른 카드로 재시도할 수 있게 대기 유지
        expect_unchanged(after_order, [p], code, what="declined, still pending")
    else:
        raise Fail("invariant", f"declined: unexpected status {status}")


def s19_refund_failure():
    """환불 중 PG 장애 → PAID 유지, 재고 불변"""
    p = new_product(stock=100)
    o = new_order(items_of((p, 2)))
    expect_ok(pay(o["id"], token="tok_refund_fail"), "pay")
    before = state([p], oid=o["id"])
    r = action(o["id"], "cancel")
    expect_no_500(r, "refund failure")
    check(not r.ok or (isinstance(r.body, dict) and r.body.get("status") == "PAID"), "invariant",
          f"refund failure reported success {r.brief()}")
    expect_unchanged(before, [p], oid=o["id"], what="refund failure")


# ---------- 견고성: 동시성 ----------

def s20_stock_race():
    """available 10에 동시 주문 20건 → 성공 정확히 10"""
    for _ in range(REPEAT):
        p = new_product(stock=10)
        results = concurrently(lambda i: create_order(items_of((p, 1))), 20)
        no_crash(results, "stock race")
        ok = sum(r.ok for r in results)
        check(ok == 10, "concurrency", f"successes {ok}, expected 10")
        expect_num(reserved(p), 10, "reserved", "concurrency")


def s21_coupon_quantity_race():
    """수량 5 쿠폰에 15명 동시 주문·결제 → 쿠폰 적용 결제 정확히 5"""
    for _ in range(REPEAT):
        code = new_coupon(totalQuantity=5)
        pids = [new_product(stock=10) for _ in range(15)]  # 사람마다 다른 상품 — 상품 잠금이 우연히 직렬화하지 않게

        def order_and_pay(i):
            r = create_order(items_of((pids[i], 1)), coupon_code=code)
            if not r.ok:
                return r
            return pay(r.body["id"])

        results = concurrently(order_and_pay, 15)
        no_crash(results, "coupon race")
        paid = sum(r.ok for r in results)
        check(paid == 5, "concurrency", f"paid with coupon {paid}, expected 5")
        expect_num(coupon(code)["usedCount"], 5, "usedCount", "concurrency")


def s22_concurrent_pay():
    """같은 주문 동시 결제(키 다름) → 청구 1회, PAID, 가용 재고 1회만 감소"""
    for _ in range(REPEAT):
        p = new_product(stock=100)
        o = new_order(items_of((p, 1)))
        results = concurrently(lambda i: pay(o["id"]), 5)
        no_crash(results, "concurrent pay")
        check(any(r.ok for r in results), "concurrency", "no payment succeeded")
        check(len(charges(o["id"])) == 1, "concurrency", f"charged {len(charges(o['id']))} times")
        check(get_order(o["id"])["status"] == "PAID", "concurrency", "status not PAID")
        expect_num(available(p), 99, "available", "concurrency")


def s23_cross_order_deadlock():
    """[P,Q]·[Q,P] 교차 동시 주문 → 5xx·교착 없음, 전부 성공, reserved 정확"""
    for _ in range(REPEAT):
        a, b = new_product(stock=100), new_product(stock=100)
        results = concurrently(
            lambda i: create_order(items_of((a, 1), (b, 1)) if i % 2 else items_of((b, 1), (a, 1))), 10)
        no_crash(results, "cross order")
        ok = sum(r.ok for r in results)
        check(ok == 10, "concurrency", f"successes {ok}, expected 10 (stock is sufficient)")
        expect_num(reserved(a), 10, "reserved a", "concurrency")
        expect_num(reserved(b), 10, "reserved b", "concurrency")


# ---------- 견고성: 목록 ----------

def s24_cursor_stable():
    """페이지 순회 중 새 주문 생성 → 처음 있던 주문이 중복·누락 없음"""
    user, p = new_user(), new_product()
    ids = [str(new_order(items_of((p, 1)), user)["id"]) for _ in range(5)]
    r = list_orders(userId=user, size=2)
    expect_status(r, 200, "page 1")
    seen = [str(o["id"]) for o in r.body["content"]]
    cursor = r.body.get("nextCursor")
    for _ in range(2):
        new_order(items_of((p, 1)), user)
    while cursor:
        r = list_orders(userId=user, size=2, cursor=cursor)
        expect_status(r, 200, "next page")
        seen += [str(o["id"]) for o in r.body["content"]]
        cursor = r.body.get("nextCursor")
        check(len(seen) < 50, "pagination", "cursor never ends")
    check(len(seen) == len(set(seen)), "pagination", f"duplicates in pages {seen}")
    missing = [i for i in ids if i not in seen]
    check(not missing, "pagination", f"missing {missing}")


def s25_invalid_list_params():
    """잘못된 size·cursor·status → 거절"""
    user = new_user()
    new_order(items_of((new_product(), 1)), user)
    fixture(list_orders(userId=user, size=10), "valid list")
    for params in ({"size": 0}, {"size": -1}, {"size": "abc"}, {"cursor": "!!not-a-cursor!!"}, {"status": "NOPE"}):
        expect_rejected(list_orders(**params), f"list {params}")


CASES = [
    ("K1", k1_product), ("K2", k2_coupon), ("K3", k3_order_create), ("K4", k4_discount), ("K5", k5_pay),
    ("K6", k6_cancel_pending), ("K7", k7_cancel_paid), ("K8", k8_ship_deliver), ("K9", k9_list),
    ("K10", k10_problem_details),
    ("S1", s1_product_validation), ("S2", s2_order_items_validation), ("S3", s3_coupon_validation),
    ("S4", s4_malformed_json), ("S5", s5_amount_overflow),
    ("S6", s6_insufficient_stock_atomic), ("S7", s7_discount_over_subtotal), ("S8", s8_coupon_conditions),
    ("S9", s9_coupon_once_per_user), ("S10", s10_restore_on_cancel_refund),
    ("S11", s11_invalid_transitions), ("S12", s12_pay_after_expiry), ("S13", s13_expiry_releases),
    ("S14", s14_create_replay), ("S15", s15_create_key_mismatch), ("S16", s16_pay_replay),
    ("S17", s17_gateway_failure), ("S18", s18_declined), ("S19", s19_refund_failure),
    ("S20", s20_stock_race), ("S21", s21_coupon_quantity_race), ("S22", s22_concurrent_pay),
    ("S23", s23_cross_order_deadlock),
    ("S24", s24_cursor_stable), ("S25", s25_invalid_list_params),
]


def in_phase(item, phase):
    return phase == "all" or (item in TTL_PHASE) == (phase == "ttl")


def summarize(results):
    """계약 = K 통과율, 견고성 = 범주별 S 통과율의 평균 (PRD §4)."""
    passed = {r["id"]: r["passed"] for r in results}
    k = [v for i, v in passed.items() if i.startswith("K")]
    category_rates = {}
    for cat, items in CATEGORIES.items():
        category_rates[cat] = round(sum(passed.get(i, False) for i in items) / len(items), 4)
    failures = {}
    for r in results:
        if not r["passed"]:
            failures[r["category"]] = failures.get(r["category"], 0) + 1
    return {
        "contract_rate": round(sum(k) / 10, 4),
        "robustness_rate": round(sum(category_rates.values()) / len(category_rates), 4),
        "category_rates": category_rates,
        "passed_items": [r["id"] for r in results if r["passed"]],
        "failed_items": [r["id"] for r in results if not r["passed"]],
        "failure_categories": failures,
        "cases": results,
    }


def main():
    global BASE, PG, TTL_SECONDS, REPEAT
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--pg-url", required=True)
    ap.add_argument("--phase", choices=["main", "ttl", "all"], default="main")
    ap.add_argument("--ttl-seconds", type=float, default=600)
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--out")
    ap.add_argument("--only", help="실행할 항목 ID, 쉼표 구분 (예: K1,S3)")
    args = ap.parse_args()
    BASE, PG, TTL_SECONDS, REPEAT = args.base_url.rstrip("/"), args.pg_url.rstrip("/"), args.ttl_seconds, args.repeat
    only = set(args.only.split(",")) if args.only else None
    results = []
    for item, fn in CASES:
        if not in_phase(item, args.phase) or (only and item not in only):
            continue
        started = time.time()
        entry = {"id": item, "case": fn.__name__, "title": (fn.__doc__ or "").strip()}
        try:
            fn()
            entry["passed"] = True
        except Fail as f:
            entry.update(passed=False, category=f.category, message=str(f))
        except Exception as e:
            entry.update(passed=False, category="error", message=f"{type(e).__name__}: {e}")
        entry["seconds"] = round(time.time() - started, 2)
        results.append(entry)
        mark = "PASS" if entry["passed"] else f"FAIL[{entry['category']}]"
        print(f"{mark:22} {item:4} {fn.__name__}" + ("" if entry["passed"] else f" — {entry['message'][:220]}"),
              flush=True)
    s = summarize(results)
    s["phase"] = args.phase
    print(f"\n{len(s['passed_items'])}/{len(results)} passed, failed: {s['failed_items']}")
    if args.out:
        with open(args.out, "w") as fh:
            json.dump(s, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
