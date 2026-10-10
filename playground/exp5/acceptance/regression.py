#!/usr/bin/env python3
"""5차 실험 회귀 케이스 — 2차 인수 테스트(feature.md 계약)를 그대로 옮긴 것.

- 케이스 본문은 2차와 같다. 바꾼 것은 call()의 기본 헤더(DEFAULT_HEADERS, T4의 X-Tenant-Id 주입용)와
  값이 None인 헤더를 빼는 처리뿐이다.
- 실행은 test_acceptance.py(--task t3|t4)가 맡는다. 이 파일의 main()은 2차 단독 실행용으로 남겨 둔다.
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
from urllib import error, request
from urllib.parse import urlencode, urljoin

BASE = ""
PG = ""
TTL_SECONDS = 600
DEFAULT_HEADERS = {}  # T4: {"X-Tenant-Id": ...}. 케이스가 넘긴 headers가 우선한다
PROBLEM_FIELDS = ("type", "title", "status", "detail", "code")
PRODUCT_FIELDS = ("id", "name", "price", "stock", "reserved", "available")
COUPON_FIELDS = ("code", "type", "value", "minOrderAmount", "maxDiscountAmount", "totalQuantity", "usedCount",
                 "validFrom", "validUntil")
ORDER_FIELDS = ("id", "userId", "status", "items", "couponCode", "subtotal", "discount", "totalPrice",
                "createdAt", "expiresAt", "paidAt")
ITEM_FIELDS = ("productId", "quantity", "unitPrice")


class Fail(Exception):
    """category: status | code | field | format | atomicity | concurrency | idempotency | pg | timing"""

    def __init__(self, category, message):
        super().__init__(message)
        self.category = category


class Resp:
    def __init__(self, status, headers, raw, elapsed):
        self.status = status
        self.headers = {k.lower(): v for k, v in headers.items()}
        self.raw = raw
        self.elapsed = elapsed
        try:
            self.body = json.loads(raw) if raw else None
        except ValueError:
            self.body = None

    @property
    def content_type(self):
        return self.headers.get("content-type", "")

    @property
    def code(self):
        return self.body.get("code") if isinstance(self.body, dict) else None


def call(method, path, body=None, raw_body=None, headers=None, url=None, timeout=30):
    target = url or BASE + path
    data = None
    h = {"Accept": "application/json, application/problem+json"}
    if not target.startswith(PG or "\0"):  # 앱 요청에만(Location 절대 URL 포함), PG 스텁 요청에는 넣지 않는다
        h.update(DEFAULT_HEADERS)
    if raw_body is not None:
        data = raw_body.encode()
        h["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    h = {k: v for k, v in h.items() if v is not None}  # None은 "이 헤더를 보내지 않음"
    req = request.Request(target, data=data, method=method, headers=h)
    started = time.time()
    try:
        with request.urlopen(req, timeout=timeout) as r:
            return Resp(r.status, dict(r.headers), r.read().decode(), time.time() - started)
    except error.HTTPError as e:
        return Resp(e.code, dict(e.headers), e.read().decode(), time.time() - started)


# ---------- assertion helpers ----------

def expect_status(resp, expected, what):
    exp = expected if isinstance(expected, (set, tuple)) else (expected,)
    if resp.status not in exp:
        raise Fail("status", f"{what}: expected {expected}, got {resp.status} body={resp.raw[:200]}")


def expect_code(resp, status, code, what):
    """같은 상태코드가 여러 의미를 가질 때(쿠폰 409 등) 의미까지 확인한다."""
    expect_status(resp, status, what)
    if resp.code != code:
        raise Fail("code", f"{what}: expected code {code}, got {resp.code!r} body={resp.raw[:200]}")


def expect_fields(obj, fields, what):
    if not isinstance(obj, dict):
        raise Fail("field", f"{what}: body is not a JSON object: {obj!r}"[:300])
    missing = [f for f in fields if f not in obj]
    if missing:
        raise Fail("field", f"{what}: missing fields {missing}")


def expect_int(value, what):
    if not isinstance(value, int) or isinstance(value, bool):
        raise Fail("field", f"{what}: expected integer, got {value!r} ({type(value).__name__})")


def expect_eq(actual, expected, what, category="field"):
    if actual != expected:
        raise Fail(category, f"{what}: expected {expected!r}, got {actual!r}")


def parse_iso(value, what):
    if not isinstance(value, str):
        raise Fail("field", f"{what}: expected ISO-8601 string, got {value!r}")
    s = value.replace("Z", "+00:00")
    s = re.sub(r"(\.\d{6})\d+", r"\1", s)
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        raise Fail("field", f"{what}: not ISO-8601: {value!r}")
    if dt.tzinfo is None:
        raise Fail("field", f"{what}: ISO-8601 without offset: {value!r}")
    return dt


def expect_problem(resp, status, what, code=None):
    expect_status(resp, status, what)
    if not resp.content_type.startswith("application/problem+json"):
        raise Fail("format", f"{what}: content-type {resp.content_type!r}")
    if not isinstance(resp.body, dict):
        raise Fail("format", f"{what}: body is not JSON object")
    missing = [f for f in PROBLEM_FIELDS if f not in resp.body]
    if missing:
        raise Fail("format", f"{what}: problem missing {missing}")
    if resp.body.get("status") != status:
        raise Fail("format", f"{what}: problem.status={resp.body.get('status')!r}")
    if code and resp.body.get("code") != code:
        raise Fail("format", f"{what}: problem.code expected {code}, got {resp.body.get('code')!r}")


def check_product(obj, what, **expected):
    expect_fields(obj, PRODUCT_FIELDS, what)
    for f in ("price", "stock", "reserved", "available"):
        expect_int(obj[f], f"{what}.{f}")
    if obj["available"] != obj["stock"] - obj["reserved"]:
        raise Fail("field", f"{what}: available {obj['available']} != stock {obj['stock']} - reserved {obj['reserved']}")
    for k, v in expected.items():
        expect_eq(obj[k], v, f"{what}.{k}", "atomicity" if k in ("stock", "reserved", "available") else "field")


def check_order(obj, what):
    expect_fields(obj, ORDER_FIELDS, what)
    for f in ("subtotal", "discount", "totalPrice"):
        expect_int(obj[f], f"{what}.{f}")
    if not isinstance(obj["items"], list) or not obj["items"]:
        raise Fail("field", f"{what}.items: expected non-empty list")
    for i, item in enumerate(obj["items"]):
        expect_fields(item, ITEM_FIELDS, f"{what}.items[{i}]")
        expect_int(item["quantity"], f"{what}.items[{i}].quantity")
        expect_int(item["unitPrice"], f"{what}.items[{i}].unitPrice")
    parse_iso(obj["createdAt"], f"{what}.createdAt")
    parse_iso(obj["expiresAt"], f"{what}.expiresAt")
    if obj["paidAt"] is not None:
        parse_iso(obj["paidAt"], f"{what}.paidAt")


def same_id(a, b):
    return str(a) == str(b)


# ---------- fixtures ----------

def hexid(n=10):
    return uuid.uuid4().hex[:n]


def new_user():
    return f"u-{hexid()}"


def new_key():
    return str(uuid.uuid4())


def new_code(n=13):
    return "C" + "".join(random.choices(string.ascii_uppercase + string.digits, k=n - 1))


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


def now():
    return datetime.now(timezone.utc)


def fixture(resp, status, what):
    if resp.status != status or not isinstance(resp.body, dict):
        raise Fail("status", f"fixture {what} failed: {resp.status} {resp.raw[:200]}")
    return resp.body


def new_product(price=1000, stock=100):
    r = call("POST", "/api/products", {"name": f"p-{hexid()}", "price": price, "stock": stock})
    return fixture(r, 201, "product create")["id"]


def product(pid):
    return fixture(call("GET", f"/api/products/{pid}"), 200, "product get")


def coupon_body(**kw):
    body = {"code": new_code(), "type": "FIXED", "value": 1000, "minOrderAmount": 0, "totalQuantity": 100,
            "validFrom": iso(now() - timedelta(hours=1)), "validUntil": iso(now() + timedelta(hours=1))}
    body.update(kw)
    return {k: v for k, v in body.items() if v is not None}


def new_coupon(**kw):
    return fixture(call("POST", "/api/coupons", coupon_body(**kw)), 201, "coupon create")["code"]


def coupon(code):
    return fixture(call("GET", f"/api/coupons/{code}"), 200, "coupon get")


def items_of(*pairs):
    return [{"productId": p, "quantity": q} for p, q in pairs]


def create_order(items, user=None, coupon_code=None, key=None, headers=None):
    body = {"items": items}
    if coupon_code is not None:
        body["couponCode"] = coupon_code
    h = {"X-User-Id": user or new_user(), "Idempotency-Key": key or new_key()}
    h.update(headers or {})
    return call("POST", "/api/orders", body, headers=h)


def new_order(items, user=None, coupon_code=None):
    return fixture(create_order(items, user, coupon_code), 201, "order create")


def get_order(oid):
    return fixture(call("GET", f"/api/orders/{oid}"), 200, "order get")


def pay(oid, token="tok_ok", key=None, body=None, timeout=30):
    b = {"cardToken": token} if body is None else body
    h = {"Idempotency-Key": key} if key != "" else {}
    if key is None:
        h = {"Idempotency-Key": new_key()}
    return call("POST", f"/api/orders/{oid}/pay", b, headers=h, timeout=timeout)


def paid_order(items, user=None, coupon_code=None, token="tok_ok"):
    o = new_order(items, user, coupon_code)
    return fixture(pay(o["id"], token), 200, "pay")


def action(oid, name):
    return call("POST", f"/api/orders/{oid}/{name}")


def pg_calls(oid, kind="payment"):
    r = call("GET", f"/__admin/calls?orderId={oid}", url=f"{PG}/__admin/calls?orderId={oid}")
    return [c for c in (r.body or []) if c.get("kind") == kind]


_SAMPLE = {}


def missing_id(kind):
    """앱이 실제로 쓰는 id 타입(정수/UUID 등)에 맞춰 존재하지 않는 id를 만든다."""
    if kind not in _SAMPLE:
        pid = new_product()
        _SAMPLE["product"] = pid
        _SAMPLE["order"] = new_order(items_of((pid, 1)))["id"]
    sample = _SAMPLE[kind]
    if isinstance(sample, int) and not isinstance(sample, bool):
        return 987654321
    if isinstance(sample, str) and re.fullmatch(r"\d+", sample):
        return "987654321"
    return str(uuid.uuid4())


def concurrently(fn, n):
    barrier = threading.Barrier(n)

    def run(i):
        barrier.wait()
        return fn(i)

    with ThreadPoolExecutor(max_workers=n) as ex:
        return list(ex.map(run, range(n)))


def sleep_until(dt, extra=0.0):
    delay = (dt - now()).total_seconds() + extra
    if delay > 0:
        time.sleep(delay)


# ---------- R1 상품 ----------

def r1_create_201():
    """R1.1 R1.3"""
    name = f"p-{hexid()}"
    r = call("POST", "/api/products", {"name": name, "price": 1500, "stock": 7})
    expect_status(r, 201, "create product")
    if not r.headers.get("location"):
        raise Fail("field", "Location header missing")
    check_product(r.body, "create body", name=name, price=1500, stock=7, reserved=0, available=7)


def r1_location_resolves():
    """R1.1 R1.3"""
    r = call("POST", "/api/products", {"name": f"p-{hexid()}", "price": 2000, "stock": 3})
    expect_status(r, 201, "create product")
    g = call("GET", None, url=urljoin(BASE + "/", r.headers.get("location") or ""))
    expect_status(g, 200, "GET Location")
    check_product(g.body, "Location body", id=r.body["id"], price=2000, stock=3)


def r1_invalid_400():
    """R1.2"""
    bad = [{"price": 1000, "stock": 1}, {"name": "   ", "price": 1000, "stock": 1},
           {"name": "x" * 101, "price": 1000, "stock": 1},
           {"name": "p", "price": 0, "stock": 1}, {"name": "p", "price": 10_000_001, "stock": 1},
           {"name": "p", "price": 1000, "stock": -1}, {"name": "p", "price": 1000, "stock": 1_000_001}]
    for body in bad:
        expect_status(call("POST", "/api/products", body), 400, f"invalid product {body}")


def r1_bounds_201():
    """R1.2"""
    for body in [{"name": "x" * 100, "price": 10_000_000, "stock": 0},
                 {"name": "p", "price": 1, "stock": 1_000_000}]:
        expect_status(call("POST", "/api/products", body), 201, f"boundary product {str(body)[:60]}")


def r1_get_404():
    """R1.3"""
    expect_status(call("GET", f"/api/products/{missing_id('product')}"), 404, "missing product")


def r1_reserved_available():
    """R1.4 R3.4"""
    pid = new_product(stock=10)
    new_order(items_of((pid, 3)))
    check_product(product(pid), "after order", stock=10, reserved=3, available=7)


# ---------- R2 쿠폰 ----------

def r2_create_201():
    """R2.1 R2.3"""
    body = coupon_body(type="RATE", value=15, minOrderAmount=5000, maxDiscountAmount=3000, totalQuantity=7)
    r = call("POST", "/api/coupons", body)
    expect_status(r, 201, "create coupon")
    if not r.headers.get("location"):
        raise Fail("field", "Location header missing")
    expect_fields(r.body, COUPON_FIELDS, "create body")
    for k in ("code", "type", "value", "minOrderAmount", "maxDiscountAmount", "totalQuantity"):
        expect_eq(r.body[k], body[k], f"create body.{k}")
    expect_eq(r.body["usedCount"], 0, "usedCount")
    expect_eq(parse_iso(r.body["validFrom"], "validFrom"), parse_iso(body["validFrom"], "sent"), "validFrom")
    g = call("GET", None, url=urljoin(BASE + "/", r.headers["location"]))
    expect_status(g, 200, "GET Location")
    expect_eq(g.body.get("code"), body["code"], "GET code")


def r2_code_rules():
    """R2.2"""
    for code in ["abcd", "AB1", "A" * 21, "AB-12", None]:
        expect_status(call("POST", "/api/coupons", coupon_body(code=code)), 400, f"code {code!r}")
    for n in (4, 20):
        expect_status(call("POST", "/api/coupons", coupon_body(code=new_code(n))), 201, f"code length {n}")


def r2_value_rules():
    """R2.2"""
    bad = [dict(type="FIXED", value=0), dict(type="RATE", value=0), dict(type="RATE", value=101),
           dict(type="PERCENT", value=10), dict(minOrderAmount=-1), dict(maxDiscountAmount=0),
           dict(totalQuantity=0)]
    for kw in bad:
        expect_status(call("POST", "/api/coupons", coupon_body(**kw)), 400, f"invalid {kw}")
    expect_status(call("POST", "/api/coupons", coupon_body(type="RATE", value=100)), 201, "RATE 100")
    no_min = coupon_body()
    no_min.pop("minOrderAmount")
    r = call("POST", "/api/coupons", no_min)
    expect_status(r, 201, "minOrderAmount omitted")
    expect_eq(r.body.get("minOrderAmount"), 0, "default minOrderAmount")


def r2_window_rules():
    """R2.2"""
    t = now()
    for f, u in [(t, t), (t + timedelta(hours=1), t)]:
        expect_status(call("POST", "/api/coupons", coupon_body(validFrom=iso(f), validUntil=iso(u))), 400,
                      "validFrom >= validUntil")
    body = coupon_body()
    body.pop("validUntil")
    expect_status(call("POST", "/api/coupons", body), 400, "validUntil missing")


def r2_duplicate_409():
    """R2.2"""
    code = new_coupon()
    expect_status(call("POST", "/api/coupons", coupon_body(code=code)), 409, "duplicate code")


def r2_get_404():
    """R2.3"""
    coupon(new_coupon())  # 엔드포인트가 존재함을 먼저 확인 (없는 경로의 404와 구분)
    expect_status(call("GET", f"/api/coupons/{new_code()}"), 404, "missing coupon")


def _discount_case(price, qty, expect_sub, expect_disc, **coupon_kw):
    pid = new_product(price=price, stock=10)
    o = new_order(items_of((pid, qty)), coupon_code=new_coupon(**coupon_kw))
    expect_eq(o["subtotal"], expect_sub, "subtotal")
    expect_eq(o["discount"], expect_disc, "discount")
    expect_eq(o["totalPrice"], expect_sub - expect_disc, "totalPrice")


def r2_fixed_discount():
    """R2.4"""
    _discount_case(10_000, 2, 20_000, 3_000, type="FIXED", value=3_000)


def r2_rate_floor():
    """R2.4"""
    _discount_case(999, 3, 2_997, 449, type="RATE", value=15)


def r2_rate_max_cap():
    """R2.4"""
    _discount_case(100_000, 1, 100_000, 10_000, type="RATE", value=50, maxDiscountAmount=10_000)


def r2_fixed_max_cap():
    """R2.4"""
    _discount_case(10_000, 1, 10_000, 3_000, type="FIXED", value=5_000, maxDiscountAmount=3_000)


def r2_capped_by_subtotal():
    """R2.4"""
    _discount_case(1_000, 1, 1_000, 1_000, type="FIXED", value=5_000)


def r2_validity_window_409():
    """R2.5"""
    pid = new_product()
    t = now()
    future = new_coupon(validFrom=iso(t + timedelta(hours=1)), validUntil=iso(t + timedelta(hours=2)))
    past = new_coupon(validFrom=iso(t - timedelta(hours=2)), validUntil=iso(t - timedelta(hours=1)))
    expect_code(create_order(items_of((pid, 1)), coupon_code=future), 409, "COUPON_NOT_APPLICABLE", "not yet valid")
    expect_code(create_order(items_of((pid, 1)), coupon_code=past), 409, "COUPON_NOT_APPLICABLE", "expired")


def r2_min_order_amount():
    """R2.5"""
    code = new_coupon(minOrderAmount=5_000, totalQuantity=10)
    below, exact = new_product(price=4_999), new_product(price=5_000)
    expect_code(create_order(items_of((below, 1)), coupon_code=code), 409, "COUPON_NOT_APPLICABLE", "subtotal 4999")
    expect_status(create_order(items_of((exact, 1)), coupon_code=code), 201, "subtotal 5000")


def r2_one_per_user():
    """R2.5"""
    pid, code, user = new_product(), new_coupon(), new_user()
    expect_status(create_order(items_of((pid, 1)), user, code), 201, "first use")
    expect_code(create_order(items_of((pid, 1)), user, code), 409, "COUPON_NOT_APPLICABLE", "same user again")
    expect_status(create_order(items_of((pid, 1)), new_user(), code), 201, "other user")


def r2_exhausted_409():
    """R2.5"""
    pid, code = new_product(), new_coupon(totalQuantity=1)
    expect_status(create_order(items_of((pid, 1)), coupon_code=code), 201, "first")
    expect_code(create_order(items_of((pid, 1)), coupon_code=code), 409, "COUPON_EXHAUSTED", "second")


def r2_used_count():
    """R2.6"""
    pid, code = new_product(), new_coupon(totalQuantity=3)
    new_order(items_of((pid, 1)), coupon_code=code)
    new_order(items_of((pid, 1)), coupon_code=code)
    expect_eq(coupon(code)["usedCount"], 2, "usedCount", "atomicity")


def r2_restore_and_reuse():
    """R2.6"""
    pid, code, user = new_product(), new_coupon(totalQuantity=1), new_user()
    o = new_order(items_of((pid, 1)), user, code)
    expect_status(action(o["id"], "cancel"), 200, "cancel")
    expect_eq(coupon(code)["usedCount"], 0, "usedCount after cancel", "atomicity")
    expect_status(create_order(items_of((pid, 1)), user, code), 201, "same user reuse")


# ---------- R3 주문 생성·조회 ----------

def r3_create_201():
    """R3.1 R3.5"""
    pid, user = new_product(price=1500, stock=10), new_user()
    r = create_order(items_of((pid, 2)), user)
    expect_status(r, 201, "create order")
    if not r.headers.get("location"):
        raise Fail("field", "Location header missing")
    o = r.body
    check_order(o, "create body")
    expect_eq(o["status"], "PENDING_PAYMENT", "status")
    expect_eq(o["userId"], user, "userId")
    expect_eq(o["couponCode"], None, "couponCode")
    expect_eq(o["paidAt"], None, "paidAt")
    expect_eq((o["subtotal"], o["discount"], o["totalPrice"]), (3000, 0, 3000), "amounts")
    expect_eq(o["items"][0]["unitPrice"], 1500, "unitPrice")
    ttl = (parse_iso(o["expiresAt"], "e") - parse_iso(o["createdAt"], "c")).total_seconds()
    if abs(ttl - TTL_SECONDS) > 2:
        raise Fail("field", f"expiresAt - createdAt = {ttl}s, expected {TTL_SECONDS}s")


def r3_location_get():
    """R3.1 R3.5"""
    pid = new_product()
    r = create_order(items_of((pid, 1)), coupon_code=new_coupon(value=100))
    expect_status(r, 201, "create order")
    g = call("GET", None, url=urljoin(BASE + "/", r.headers.get("location") or ""))
    expect_status(g, 200, "GET Location")
    check_order(g.body, "GET body")
    for k in ("id", "status", "subtotal", "discount", "totalPrice", "couponCode", "userId"):
        expect_eq(g.body[k], r.body[k], f"GET {k}")


def r3_headers_400():
    """R3.1 R3.2"""
    pid = new_product()
    body = {"items": items_of((pid, 1))}
    cases = [({"Idempotency-Key": new_key()}, "no X-User-Id"),
             ({"X-User-Id": "   ", "Idempotency-Key": new_key()}, "blank X-User-Id"),
             ({"X-User-Id": "u" * 51, "Idempotency-Key": new_key()}, "X-User-Id 51"),
             ({"X-User-Id": new_user()}, "no Idempotency-Key"),
             ({"X-User-Id": new_user(), "Idempotency-Key": "k" * 65}, "key 65")]
    for h, what in cases:
        expect_status(call("POST", "/api/orders", body, headers=h), 400, what)
    ok = {"X-User-Id": "u" * 50, "Idempotency-Key": "k" * 63 + hexid(1)}
    expect_status(call("POST", "/api/orders", body, headers=ok), 201, "X-User-Id 50 / key 64")


def r3_items_400():
    """R3.2"""
    pid = new_product()
    many = [new_product() for _ in range(21)]
    bad = [{}, {"items": []}, {"items": items_of(*[(p, 1) for p in many])},
           {"items": items_of((pid, 0))}, {"items": items_of((pid, 1001))},
           {"items": items_of((pid, 1), (pid, 2))}]
    for body in bad:
        r = call("POST", "/api/orders", body, headers={"X-User-Id": new_user(), "Idempotency-Key": new_key()})
        expect_status(r, 400, f"invalid items {str(body)[:80]}")


def r3_items_bounds_201():
    """R3.2"""
    many = [new_product(stock=10) for _ in range(20)]
    expect_status(create_order(items_of(*[(p, 1) for p in many])), 201, "20 items")
    expect_status(create_order(items_of((new_product(stock=1000), 1000))), 201, "quantity 1000")


def r3_large_amount():
    """C1 R2.4"""
    pid = new_product(price=10_000_000, stock=1000)
    o = new_order(items_of((pid, 1000)))
    expect_eq(o["subtotal"], 10_000_000_000, "subtotal")
    expect_eq(o["totalPrice"], 10_000_000_000, "totalPrice")


def r3_unknown_product_404():
    """R3.3"""
    expect_status(create_order(items_of((missing_id("product"), 1))), 404, "unknown product")


def r3_unknown_coupon_404():
    """R3.3"""
    expect_status(create_order(items_of((new_product(), 1)), coupon_code=new_code()), 404, "unknown coupon")


def r3_insufficient_available_409():
    """R3.3 R1.4"""
    pid = new_product(stock=5)
    new_order(items_of((pid, 3)))
    expect_status(create_order(items_of((pid, 3))), 409, "available 2, order 3")
    expect_status(create_order(items_of((pid, 2))), 201, "available 2, order 2")


def r3_atomic_stock():
    """R3.4"""
    a, b = new_product(stock=10), new_product(stock=1)
    expect_status(create_order(items_of((a, 3), (b, 2))), 409, "partial insufficient")
    check_product(product(a), "product a", reserved=0, available=10)


def r3_atomic_coupon():
    """R3.4"""
    pid, code = new_product(stock=1), new_coupon()
    expect_status(create_order(items_of((pid, 2)), coupon_code=code), 409, "insufficient with coupon")
    expect_eq(coupon(code)["usedCount"], 0, "usedCount", "atomicity")


def r3_get_404():
    """R3.5"""
    expect_status(call("GET", f"/api/orders/{missing_id('order')}"), 404, "missing order")


def r3_precedence_400_404():
    """C3"""
    expect_status(create_order(items_of((missing_id("product"), 0))), 400, "invalid qty + unknown product")


def r3_precedence_404_409():
    """C3"""
    a = new_product(stock=1)
    expect_status(create_order(items_of((a, 5), (missing_id("product"), 1))), 404, "insufficient + unknown")


def r3_precedence_coupon404_stock409():
    """C3"""
    a = new_product(stock=1)
    expect_status(create_order(items_of((a, 5)), coupon_code=new_code()), 404, "insufficient + unknown coupon")


def r3_precedence_stock_coupon():
    """C3"""
    a, code = new_product(stock=1), new_coupon(totalQuantity=1)
    new_order(items_of((new_product(), 1)), coupon_code=code)
    expect_code(create_order(items_of((a, 5)), coupon_code=code), 409, "INSUFFICIENT_STOCK",
                "insufficient + exhausted")


# ---------- R4 멱등성 ----------

def r4_replay_create():
    """R4.2"""
    pid, user, key = new_product(stock=10), new_user(), new_key()
    code = new_coupon()
    r1 = create_order(items_of((pid, 2)), user, code, key)
    expect_status(r1, 201, "first")
    r2 = create_order(items_of((pid, 2)), user, code, key)
    expect_status(r2, 201, "replay")
    expect_eq(r2.body, r1.body, "replay body", "idempotency")
    expect_eq(product(pid)["reserved"], 2, "reserved after replay", "idempotency")
    expect_eq(coupon(code)["usedCount"], 1, "usedCount after replay", "idempotency")


def r4_replay_original_snapshot():
    """R4.2"""
    pid, user, key = new_product(stock=10), new_user(), new_key()
    r1 = create_order(items_of((pid, 1)), user, key=key)
    expect_status(r1, 201, "first")
    expect_status(pay(r1.body["id"]), 200, "pay")
    r2 = create_order(items_of((pid, 1)), user, key=key)
    expect_status(r2, 201, "replay after pay")
    expect_eq(r2.body.get("status"), "PENDING_PAYMENT", "replayed status (original response)", "idempotency")
    expect_eq(r2.body.get("id"), r1.body["id"], "replayed id", "idempotency")


def r4_mismatch_body_422():
    """R4.3"""
    pid, user, key = new_product(), new_user(), new_key()
    expect_status(create_order(items_of((pid, 1)), user, key=key), 201, "first")
    expect_status(create_order(items_of((pid, 2)), user, key=key), 422, "same key, other body")


def r4_mismatch_user_422():
    """R4.3"""
    pid, key = new_product(), new_key()
    expect_status(create_order(items_of((pid, 1)), new_user(), key=key), 201, "first")
    expect_status(create_order(items_of((pid, 1)), new_user(), key=key), 422, "same key, other user")


def r4_key_spaces_independent():
    """R4.1"""
    key = new_key()
    r = create_order(items_of((new_product(), 1)), key=key)
    expect_status(r, 201, "create")
    expect_status(pay(r.body["id"], key=key), 200, "pay with same key string")


def r4_failed_retry_409():
    """R4.4"""
    pid = new_product(stock=1)
    blocker = new_order(items_of((pid, 1)))
    user, key = new_user(), new_key()
    expect_status(create_order(items_of((pid, 1)), user, key=key), 409, "first (no stock)")
    expect_status(action(blocker["id"], "cancel"), 200, "cancel blocker")
    expect_status(create_order(items_of((pid, 1)), user, key=key), 201, "retry same key")


def r4_failed_retry_404():
    """R4.4"""
    pid, user, key, code = new_product(), new_user(), new_key(), new_code()
    expect_status(create_order(items_of((pid, 1)), user, code, key), 404, "first (no coupon)")
    expect_status(call("POST", "/api/coupons", coupon_body(code=code)), 201, "create coupon")
    expect_status(create_order(items_of((pid, 1)), user, code, key), 201, "retry same key")


def r4_concurrent_same_key():
    """R4.5"""
    pid, user, key = new_product(stock=100), new_user(), new_key()
    rs = concurrently(lambda i: create_order(items_of((pid, 2)), user, key=key), 10)
    statuses = [r.status for r in rs]
    if any(s not in (201, 409) for s in statuses) or 201 not in statuses:
        raise Fail("idempotency", f"statuses {statuses}")
    ids = {str(r.body["id"]) for r in rs if r.status == 201}
    if len(ids) != 1:
        raise Fail("idempotency", f"201 responses carry different ids {ids}")
    expect_eq(product(pid)["reserved"], 2, "reserved (processed once)", "idempotency")


def r4_pay_replay():
    """R4.2 R5.3"""
    o, key = new_order(items_of((new_product(), 1))), new_key()
    r1 = pay(o["id"], key=key)
    expect_status(r1, 200, "pay")
    r2 = pay(o["id"], key=key)
    expect_status(r2, 200, "replay pay")
    expect_eq(r2.body, r1.body, "replay body", "idempotency")
    expect_eq(len(pg_calls(o["id"])), 1, "PG payment calls", "idempotency")


def r4_pay_mismatch_422():
    """R4.3"""
    a, b, key = new_order(items_of((new_product(), 1))), new_order(items_of((new_product(), 1))), new_key()
    expect_status(pay(a["id"], key=key), 200, "pay a")
    expect_status(pay(a["id"], token="tok_decline", key=key), 422, "same key, other body")
    expect_status(pay(b["id"], key=key), 422, "same key, other order")


def r4_precedence_400_422():
    """C3 R4.3"""
    pid, user, key = new_product(), new_user(), new_key()
    expect_status(create_order(items_of((pid, 1)), user, key=key), 201, "first")
    expect_status(create_order(items_of((pid, 0)), user, key=key), 400, "same key, invalid body")


# ---------- R5 결제 ----------

def r5_approve():
    """R5.3 R5.4"""
    pid, key = new_product(price=1500, stock=10), new_key()
    o = new_order(items_of((pid, 2)), coupon_code=new_coupon(value=500))
    r = pay(o["id"], key=key)
    expect_status(r, 200, "pay")
    check_order(r.body, "pay body")
    expect_eq(r.body["status"], "PAID", "status")
    parse_iso(r.body["paidAt"], "paidAt")
    check_product(product(pid), "product", stock=8, reserved=0)
    calls = pg_calls(o["id"])
    expect_eq(len(calls), 1, "PG calls", "pg")
    c = calls[0]
    expect_eq((c["amount"], c["cardToken"], c["idempotencyKey"]), (2500, "tok_ok", key), "PG request", "pg")


def r5_decline_402():
    """R5.5"""
    pid, code = new_product(stock=10), new_coupon(value=100)
    o = new_order(items_of((pid, 3)), coupon_code=code)
    expect_status(pay(o["id"], token="tok_decline"), 402, "declined")
    expect_eq(get_order(o["id"])["status"], "PAYMENT_FAILED", "status")
    check_product(product(pid), "product", stock=10, reserved=0)
    expect_eq(coupon(code)["usedCount"], 0, "usedCount", "atomicity")


def r5_pg_error_503():
    """R5.6"""
    pid, code = new_product(stock=10), new_coupon(value=100)
    o = new_order(items_of((pid, 3)), coupon_code=code)
    expect_status(pay(o["id"], token="tok_error"), 503, "PG 500")
    g = get_order(o["id"])
    expect_eq((g["status"], g["paidAt"]), ("PENDING_PAYMENT", None), "order unchanged", "atomicity")
    check_product(product(pid), "product", stock=10, reserved=3)
    expect_eq(coupon(code)["usedCount"], 1, "usedCount", "atomicity")


def r5_pg_timeout_503():
    """R5.6"""
    pid = new_product(stock=10)
    o = new_order(items_of((pid, 1)))
    r = pay(o["id"], token="tok_slow")
    expect_status(r, 503, "PG slow")
    if r.elapsed > 4.5:
        raise Fail("timing", f"responded after {r.elapsed:.1f}s (PG timeout must be 2s)")
    expect_eq(get_order(o["id"])["status"], "PENDING_PAYMENT", "status", "atomicity")
    check_product(product(pid), "product", stock=10, reserved=1)


def r5_retry_same_pg_key():
    """R5.3 R4.4"""
    o, key = new_order(items_of((new_product(), 1))), new_key()
    started = time.time()
    expect_status(pay(o["id"], token="tok_slow", key=key), 503, "first (PG slow)")
    time.sleep(max(0.0, 5.8 - (time.time() - started)))
    expect_status(pay(o["id"], token="tok_slow", key=key), 200, "retry with same key")
    keys = {c["idempotencyKey"] for c in pg_calls(o["id"])}
    expect_eq(keys, {key}, "PG Idempotency-Key across retries", "pg")


def r5_invalid_state_409():
    """R5.2"""
    paid = paid_order(items_of((new_product(), 1)))
    expect_status(pay(paid["id"]), 409, "pay PAID")
    c = new_order(items_of((new_product(), 1)))
    expect_status(action(c["id"], "cancel"), 200, "cancel")
    expect_status(pay(c["id"]), 409, "pay CANCELLED")


def r5_not_found_404():
    """R5.2"""
    expect_status(pay(missing_id("order")), 404, "pay missing order")


def r5_card_token_400():
    """R5.1"""
    o = new_order(items_of((new_product(), 1)))
    expect_status(pay(o["id"], body={"cardToken": "   "}), 400, "blank cardToken")
    expect_status(pay(o["id"], body={}), 400, "missing cardToken")
    expect_status(pay(o["id"], key=""), 400, "missing Idempotency-Key")


def r5_zero_total_no_pg():
    """R5.7"""
    pid = new_product(price=1000, stock=10)
    o = new_order(items_of((pid, 1)), coupon_code=new_coupon(value=1000))
    expect_eq(o["totalPrice"], 0, "totalPrice")
    r = pay(o["id"])
    expect_status(r, 200, "pay zero total")
    expect_eq(r.body.get("status"), "PAID", "status")
    expect_eq(len(pg_calls(o["id"])), 0, "PG calls", "pg")
    check_product(product(pid), "product", stock=9, reserved=0)


# ---------- R6 결제 만료 (ttl 단계) ----------

def r6_expires_releases():
    """R6.1 R6.2"""
    pid, code = new_product(stock=10), new_coupon(value=100)
    o = new_order(items_of((pid, 4)), coupon_code=code)
    sleep_until(parse_iso(o["expiresAt"], "expiresAt"), 2.5)
    expect_eq(get_order(o["id"])["status"], "EXPIRED", "status", "timing")
    check_product(product(pid), "product", stock=10, reserved=0)
    expect_eq(coupon(code)["usedCount"], 0, "usedCount", "atomicity")


def r6_expires_at_ttl():
    """R3.5 R6.1"""
    o = new_order(items_of((new_product(), 1)))
    ttl = (parse_iso(o["expiresAt"], "e") - parse_iso(o["createdAt"], "c")).total_seconds()
    if abs(ttl - TTL_SECONDS) > 1:
        raise Fail("field", f"expiresAt - createdAt = {ttl}s, expected {TTL_SECONDS}s")


def r6_pay_after_expiry_409():
    """R5.2 R6.1"""
    o = new_order(items_of((new_product(), 1)))
    sleep_until(parse_iso(o["expiresAt"], "expiresAt"), 0.2)
    expect_status(pay(o["id"]), 409, "pay after expiresAt")
    expect_eq(len(pg_calls(o["id"])), 0, "PG calls", "pg")


def r6_paid_not_expired():
    """R6.1"""
    pid = new_product(stock=10)
    o = paid_order(items_of((pid, 2)))
    sleep_until(parse_iso(o["expiresAt"], "expiresAt"), 2.5)
    expect_eq(get_order(o["id"])["status"], "PAID", "status")
    check_product(product(pid), "product", stock=8, reserved=0)


# ---------- R7 취소·환불 ----------

def r7_cancel_pending():
    """R7.1 R7.2"""
    pid, code = new_product(stock=10), new_coupon(value=100)
    o = new_order(items_of((pid, 4)), coupon_code=code)
    r = action(o["id"], "cancel")
    expect_status(r, 200, "cancel")
    check_order(r.body, "cancel body")
    expect_eq(r.body["status"], "CANCELLED", "status")
    check_product(product(pid), "product", stock=10, reserved=0)
    expect_eq(coupon(code)["usedCount"], 0, "usedCount", "atomicity")


def r7_refund_paid():
    """R7.3"""
    pid, code = new_product(stock=10), new_coupon(value=100)
    o = paid_order(items_of((pid, 3)), coupon_code=code)
    r = action(o["id"], "cancel")
    expect_status(r, 200, "cancel paid")
    expect_eq(r.body.get("status"), "REFUNDED", "status")
    check_product(product(pid), "product", stock=10, reserved=0)
    expect_eq(coupon(code)["usedCount"], 0, "usedCount", "atomicity")
    expect_eq(len(pg_calls(o["id"], "refund")), 1, "PG refund calls", "pg")


def r7_refund_fail_503():
    """R7.3"""
    pid, code = new_product(stock=10), new_coupon(value=100)
    o = paid_order(items_of((pid, 3)), coupon_code=code, token="tok_refund_fail")
    expect_status(action(o["id"], "cancel"), 503, "refund fails")
    expect_eq(get_order(o["id"])["status"], "PAID", "status", "atomicity")
    check_product(product(pid), "product", stock=7, reserved=0)
    expect_eq(coupon(code)["usedCount"], 1, "usedCount", "atomicity")


def r7_invalid_states_409():
    """R7.4"""
    c = new_order(items_of((new_product(), 1)))
    action(c["id"], "cancel")
    expect_status(action(c["id"], "cancel"), 409, "cancel CANCELLED")
    s = paid_order(items_of((new_product(), 1)))
    expect_status(action(s["id"], "ship"), 200, "ship")
    expect_status(action(s["id"], "cancel"), 409, "cancel SHIPPED")
    f = new_order(items_of((new_product(), 1)))
    expect_status(pay(f["id"], token="tok_decline"), 402, "decline")
    expect_status(action(f["id"], "cancel"), 409, "cancel PAYMENT_FAILED")


def r7_not_found_404():
    """R7.1"""
    expect_status(action(missing_id("order"), "cancel"), 404, "cancel missing")


# ---------- R8 배송 ----------

def r8_ship_deliver():
    """R8.1"""
    o = paid_order(items_of((new_product(), 1)))
    r = action(o["id"], "ship")
    expect_status(r, 200, "ship")
    check_order(r.body, "ship body")
    expect_eq(r.body["status"], "SHIPPED", "status")
    r = action(o["id"], "deliver")
    expect_status(r, 200, "deliver")
    expect_eq(r.body.get("status"), "DELIVERED", "status")


def r8_invalid_transitions_409():
    """R8.2"""
    p = new_order(items_of((new_product(), 1)))
    expect_status(action(p["id"], "ship"), 409, "ship PENDING_PAYMENT")
    o = paid_order(items_of((new_product(), 1)))
    expect_status(action(o["id"], "deliver"), 409, "deliver PAID")
    action(o["id"], "ship")
    expect_status(action(o["id"], "ship"), 409, "ship SHIPPED")
    action(o["id"], "deliver")
    expect_status(action(o["id"], "deliver"), 409, "deliver DELIVERED")
    expect_status(action(o["id"], "ship"), 409, "ship DELIVERED")


def r8_not_found_404():
    """R8.2"""
    mid = missing_id("order")
    expect_status(action(mid, "ship"), 404, "ship missing")
    expect_status(action(mid, "deliver"), 404, "deliver missing")


# ---------- R9 주문 목록 ----------

def list_orders(**params):
    return call("GET", "/api/orders?" + urlencode(params))


def orders_for(user, n, pid=None):
    pid = pid or new_product(stock=1000)
    ids = []
    for _ in range(n):
        ids.append(new_order(items_of((pid, 1)), user)["id"])
        time.sleep(0.01)
    return [str(i) for i in ids]


def walk(user, size, between=None):
    ids, cursor, pages = [], None, []
    for _ in range(50):
        params = {"userId": user, "size": size}
        if cursor:
            params["cursor"] = cursor
        r = list_orders(**params)
        expect_status(r, 200, f"page {len(pages)}")
        expect_fields(r.body, ("content", "nextCursor"), "page")
        pages.append(len(r.body["content"]))
        ids += [str(o["id"]) for o in r.body["content"]]
        cursor = r.body["nextCursor"]
        if between and len(pages) == 1:
            between()
        if not cursor:
            return ids, pages
    raise Fail("field", "cursor never ended")


def r9_shape_user_filter():
    """R9.1 R9.2"""
    user = new_user()
    created = orders_for(user, 3)
    new_order(items_of((new_product(), 1)))
    r = list_orders(userId=user)
    expect_status(r, 200, "list")
    expect_fields(r.body, ("content", "nextCursor"), "page")
    expect_eq(sorted(str(o["id"]) for o in r.body["content"]), sorted(created), "user filter")
    expect_eq(r.body["nextCursor"], None, "nextCursor")
    check_order(r.body["content"][0], "content[0]")


def r9_sorted_desc():
    """R9.4"""
    user = new_user()
    created = orders_for(user, 4)
    r = list_orders(userId=user)
    expect_status(r, 200, "list")
    expect_eq([str(o["id"]) for o in r.body["content"]], created[::-1], "order (createdAt desc)")


def r9_status_filter():
    """R9.2"""
    user, pid = new_user(), new_product(stock=100)
    pending = str(new_order(items_of((pid, 1)), user)["id"])
    paid = {str(paid_order(items_of((pid, 1)), user)["id"]) for _ in range(2)}
    r = list_orders(userId=user, status="PAID")
    expect_status(r, 200, "list PAID")
    expect_eq({str(o["id"]) for o in r.body["content"]}, paid, "status=PAID & userId")
    r = list_orders(userId=user, status="PENDING_PAYMENT")
    expect_eq({str(o["id"]) for o in r.body["content"]}, {pending}, "status=PENDING_PAYMENT & userId")


def r9_cursor_paging():
    """R9.1 R9.3"""
    user = new_user()
    created = orders_for(user, 5)
    ids, pages = walk(user, 2)
    expect_eq(pages, [2, 2, 1], "page sizes")
    expect_eq(ids, created[::-1], "all orders in order, once")


def r9_last_page_null():
    """R9.1"""
    user = new_user()
    orders_for(user, 4)
    ids, pages = walk(user, 2)
    expect_eq(pages, [2, 2], "page sizes (nextCursor null when no next page)")


def r9_stable_under_insert():
    """R9.5"""
    user, pid = new_user(), new_product(stock=1000)
    created = orders_for(user, 5, pid)
    ids, _ = walk(user, 2, between=lambda: orders_for(user, 2, pid))
    expect_eq(ids, created[::-1], "pages after insert (no dup/missing)")


def r9_default_size():
    """R9.3"""
    user = new_user()
    orders_for(user, 21)
    r = list_orders(userId=user)
    expect_status(r, 200, "list")
    expect_eq(len(r.body["content"]), 20, "default size")
    if not r.body.get("nextCursor"):
        raise Fail("field", "nextCursor missing on first of two pages")
    r2 = list_orders(userId=user, cursor=r.body["nextCursor"])
    expect_status(r2, 200, "page 2")
    expect_eq(len(r2.body["content"]), 1, "page 2 size")


def r9_invalid_params_400():
    """R9.2 R9.3"""
    for params in [{"size": 0}, {"size": 101}, {"status": "NOPE"}, {"cursor": "@@not-a-cursor@@"}]:
        expect_status(list_orders(**params), 400, f"list {params}")
    expect_status(list_orders(size=100), 200, "size 100")


# ---------- R10 동시성 (각 3회 반복) ----------

REPS = 3


def r10_stock_race():
    """R10.1"""
    for rep in range(REPS):
        pid = new_product(stock=10)
        st = [r.status for r in concurrently(lambda i: create_order(items_of((pid, 1))), 20)]
        if st.count(201) != 10 or st.count(409) != 10:
            raise Fail("concurrency", f"rep {rep}: {sorted(st)}")
        expect_eq(product(pid)["reserved"], 10, f"rep {rep} reserved", "concurrency")


def r10_coupon_quantity_race():
    """R10.2"""
    for rep in range(REPS):
        pid, code = new_product(stock=100), new_coupon(totalQuantity=5)
        st = [r.status for r in concurrently(lambda i: create_order(items_of((pid, 1)), coupon_code=code), 15)]
        if st.count(201) != 5 or st.count(409) != 10:
            raise Fail("concurrency", f"rep {rep}: {sorted(st)}")
        expect_eq(coupon(code)["usedCount"], 5, f"rep {rep} usedCount", "concurrency")


def r10_same_user_coupon_race():
    """R10.3"""
    for rep in range(REPS):
        pid, code, user = new_product(stock=100), new_coupon(totalQuantity=100), new_user()
        st = [r.status for r in concurrently(lambda i: create_order(items_of((pid, 1)), user, code), 5)]
        if st.count(201) != 1:
            raise Fail("concurrency", f"rep {rep}: {sorted(st)}")
        expect_eq(coupon(code)["usedCount"], 1, f"rep {rep} usedCount", "concurrency")


def r10_cross_order_no_deadlock():
    """R10.4"""
    for rep in range(REPS):
        p, q = new_product(stock=1000), new_product(stock=1000)
        st = [r.status for r in concurrently(
            lambda i: create_order(items_of((p, 1), (q, 1)) if i % 2 else items_of((q, 1), (p, 1))), 20)]
        if st.count(201) != 20:
            raise Fail("concurrency", f"rep {rep}: {sorted(st)}")
        expect_eq((product(p)["reserved"], product(q)["reserved"]), (20, 20), f"rep {rep} reserved", "concurrency")


def r10_concurrent_pay():
    """R10.5"""
    for rep in range(REPS):
        o = new_order(items_of((new_product(), 1)))
        st = [r.status for r in concurrently(lambda i: pay(o["id"]), 5)]
        if st.count(200) != 1 or any(s >= 500 for s in st):
            raise Fail("concurrency", f"rep {rep}: {sorted(st)}")
        expect_eq(len(pg_calls(o["id"])), 1, f"rep {rep} PG payment calls", "concurrency")


# ---------- R11 에러 포맷 ----------

def r11_validation_problem():
    """R11.1 R11.2 R11.3"""
    expect_problem(call("POST", "/api/products", {"name": "", "price": -1, "stock": 1}), 400, "validation",
                   "VALIDATION_ERROR")


def r11_malformed_json():
    """R11.1 R11.3"""
    expect_problem(call("POST", "/api/products", raw_body='{"name": "x", "price": '), 400, "malformed JSON",
                   "VALIDATION_ERROR")


def r11_header_problem():
    """R11.1 R11.3"""
    r = call("POST", "/api/orders", {"items": items_of((new_product(), 1))}, headers={"Idempotency-Key": new_key()})
    expect_problem(r, 400, "missing X-User-Id", "VALIDATION_ERROR")


def r11_query_problem():
    """R11.1 R11.3"""
    expect_problem(list_orders(size=0), 400, "size=0", "VALIDATION_ERROR")
    expect_problem(list_orders(status="NOPE"), 400, "status=NOPE", "VALIDATION_ERROR")


def r11_not_found_codes():
    """R11.3"""
    expect_problem(call("GET", f"/api/products/{missing_id('product')}"), 404, "product", "PRODUCT_NOT_FOUND")
    expect_problem(call("GET", f"/api/coupons/{new_code()}"), 404, "coupon", "COUPON_NOT_FOUND")
    expect_problem(call("GET", f"/api/orders/{missing_id('order')}"), 404, "order", "ORDER_NOT_FOUND")


def r11_conflict_codes():
    """R11.3"""
    expect_problem(create_order(items_of((new_product(stock=1), 2))), 409, "stock", "INSUFFICIENT_STOCK")
    code = new_coupon(totalQuantity=1)
    expect_problem(call("POST", "/api/coupons", coupon_body(code=code)), 409, "dup coupon", "DUPLICATE_COUPON_CODE")
    pid = new_product()
    new_order(items_of((pid, 1)), coupon_code=code)
    expect_problem(create_order(items_of((pid, 1)), coupon_code=code), 409, "exhausted", "COUPON_EXHAUSTED")
    later = new_coupon(validFrom=iso(now() + timedelta(hours=1)), validUntil=iso(now() + timedelta(hours=2)))
    expect_problem(create_order(items_of((pid, 1)), coupon_code=later), 409, "not applicable",
                   "COUPON_NOT_APPLICABLE")
    o = new_order(items_of((pid, 1)))
    action(o["id"], "cancel")
    expect_problem(action(o["id"], "cancel"), 409, "invalid state", "INVALID_STATE")


def r11_payment_codes():
    """R11.3"""
    a = new_order(items_of((new_product(), 1)))
    expect_problem(pay(a["id"], token="tok_decline"), 402, "declined", "PAYMENT_DECLINED")
    b = new_order(items_of((new_product(), 1)))
    expect_problem(pay(b["id"], token="tok_error"), 503, "gateway", "PAYMENT_GATEWAY_UNAVAILABLE")


def r11_idempotency_code():
    """R11.3"""
    pid, user, key = new_product(), new_user(), new_key()
    create_order(items_of((pid, 1)), user, key=key)
    expect_problem(create_order(items_of((pid, 2)), user, key=key), 422, "mismatch", "IDEMPOTENCY_KEY_MISMATCH")


CASES = [
    ("R1", r1_create_201), ("R1", r1_location_resolves), ("R1", r1_invalid_400), ("R1", r1_bounds_201),
    ("R1", r1_get_404), ("R1", r1_reserved_available),
    ("R2", r2_create_201), ("R2", r2_code_rules), ("R2", r2_value_rules), ("R2", r2_window_rules),
    ("R2", r2_duplicate_409), ("R2", r2_get_404), ("R2", r2_fixed_discount), ("R2", r2_rate_floor),
    ("R2", r2_rate_max_cap), ("R2", r2_fixed_max_cap), ("R2", r2_capped_by_subtotal),
    ("R2", r2_validity_window_409), ("R2", r2_min_order_amount), ("R2", r2_one_per_user),
    ("R2", r2_exhausted_409), ("R2", r2_used_count), ("R2", r2_restore_and_reuse),
    ("R3", r3_create_201), ("R3", r3_location_get), ("R3", r3_headers_400), ("R3", r3_items_400),
    ("R3", r3_items_bounds_201), ("R3", r3_large_amount), ("R3", r3_unknown_product_404),
    ("R3", r3_unknown_coupon_404), ("R3", r3_insufficient_available_409), ("R3", r3_atomic_stock),
    ("R3", r3_atomic_coupon), ("R3", r3_get_404), ("R3", r3_precedence_400_404), ("R3", r3_precedence_404_409),
    ("R3", r3_precedence_coupon404_stock409), ("R3", r3_precedence_stock_coupon),
    ("R4", r4_replay_create), ("R4", r4_replay_original_snapshot), ("R4", r4_mismatch_body_422),
    ("R4", r4_mismatch_user_422), ("R4", r4_key_spaces_independent), ("R4", r4_failed_retry_409),
    ("R4", r4_failed_retry_404), ("R4", r4_concurrent_same_key), ("R4", r4_pay_replay),
    ("R4", r4_pay_mismatch_422), ("R4", r4_precedence_400_422),
    ("R5", r5_approve), ("R5", r5_decline_402), ("R5", r5_pg_error_503), ("R5", r5_pg_timeout_503),
    ("R5", r5_retry_same_pg_key), ("R5", r5_invalid_state_409), ("R5", r5_not_found_404),
    ("R5", r5_card_token_400), ("R5", r5_zero_total_no_pg),
    ("R6", r6_expires_releases), ("R6", r6_expires_at_ttl), ("R6", r6_pay_after_expiry_409),
    ("R6", r6_paid_not_expired),
    ("R7", r7_cancel_pending), ("R7", r7_refund_paid), ("R7", r7_refund_fail_503), ("R7", r7_invalid_states_409),
    ("R7", r7_not_found_404),
    ("R8", r8_ship_deliver), ("R8", r8_invalid_transitions_409), ("R8", r8_not_found_404),
    ("R9", r9_shape_user_filter), ("R9", r9_sorted_desc), ("R9", r9_status_filter), ("R9", r9_cursor_paging),
    ("R9", r9_last_page_null), ("R9", r9_stable_under_insert), ("R9", r9_default_size),
    ("R9", r9_invalid_params_400),
    ("R10", r10_stock_race), ("R10", r10_coupon_quantity_race), ("R10", r10_same_user_coupon_race),
    ("R10", r10_cross_order_no_deadlock), ("R10", r10_concurrent_pay),
    ("R11", r11_validation_problem), ("R11", r11_malformed_json), ("R11", r11_header_problem),
    ("R11", r11_query_problem), ("R11", r11_not_found_codes), ("R11", r11_conflict_codes),
    ("R11", r11_payment_codes), ("R11", r11_idempotency_code),
]
REQUIREMENTS = [f"R{i}" for i in range(1, 12)]


def summarize(results):
    by_req = {}
    for r in results:
        by_req.setdefault(r["req"], []).append(r["passed"])
    req_rates = {k: round(sum(v) / len(v), 4) for k, v in sorted(by_req.items(), key=lambda kv: int(kv[0][1:]))}
    passed = sum(r["passed"] for r in results)
    categories = {}
    for r in results:
        if not r["passed"]:
            categories[r["category"]] = categories.get(r["category"], 0) + 1
    return {
        "passed": passed,
        "total": len(results),
        "pass_rate": round(passed / len(results), 4) if results else 0.0,
        "requirement_rates": req_rates,
        "requirement_mean": round(sum(req_rates.get(r, 0.0) for r in REQUIREMENTS) / len(REQUIREMENTS), 4),
        "requirements_met": [k for k, v in req_rates.items() if v == 1.0],
        "failure_categories": categories,
        "cases": results,
    }


def main():
    global BASE, PG, TTL_SECONDS
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--pg-url", required=True)
    ap.add_argument("--phase", choices=["main", "ttl", "all"], default="main")
    ap.add_argument("--ttl-seconds", type=float, default=600)
    ap.add_argument("--out")
    ap.add_argument("--only", help="실행할 요구사항 ID, 쉼표 구분")
    ap.add_argument("--skip", help="제외할 케이스 함수명, 쉼표 구분")
    args = ap.parse_args()
    BASE, PG, TTL_SECONDS = args.base_url.rstrip("/"), args.pg_url.rstrip("/"), args.ttl_seconds

    only = set(args.only.split(",")) if args.only else None
    skip = set(args.skip.split(",")) if args.skip else set()
    phase_ok = {"main": lambda r: r != "R6", "ttl": lambda r: r == "R6", "all": lambda r: True}[args.phase]
    cases = [(r, f) for r, f in CASES
             if phase_ok(r) and (only is None or r in only) and f.__name__ not in skip]

    results = []
    for req, fn in cases:
        started = time.time()
        entry = {"id": fn.__name__, "req": req, "rules": (fn.__doc__ or "").split()}
        try:
            fn()
            entry["passed"] = True
        except Fail as f:
            entry.update(passed=False, category=f.category, message=str(f))
        except Exception as e:  # 연결 실패·타임아웃 등
            entry.update(passed=False, category="error", message=f"{type(e).__name__}: {e}")
        entry["seconds"] = round(time.time() - started, 2)
        results.append(entry)
        mark = "PASS" if entry["passed"] else f"FAIL[{entry['category']}]"
        print(f"{mark:22} {req:4} {fn.__name__}" + ("" if entry["passed"] else f" — {entry['message'][:200]}"),
              flush=True)

    summary = summarize(results)
    summary["phase"] = args.phase
    print(f"\n{summary['passed']}/{summary['total']} passed ({summary['pass_rate']:.0%}), "
          f"requirement rates: {summary['requirement_rates']}")
    if args.out:
        with open(args.out, "w") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
