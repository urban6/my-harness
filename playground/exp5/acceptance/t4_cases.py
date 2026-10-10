"""T4 숨긴 테스트 — tasks/t4/change-request.md (멀티테넌시).

- 케이스마다 docstring에 근거 규칙 번호를 단다. 명세 내(spec) 케이스는 change-request.md·feature.md 문구에서만 도출한다.
- 명세 해석(interp) 케이스는 명세에 없는 상식적 기대다. 주 지표에 넣지 않는다.
- 회귀 케이스는 regression.DEFAULT_HEADERS로 고정 테넌트 하나를 주입해 돌린다(test_acceptance.py).
- 테넌트는 헤더로만 넘긴다(스레드 안전 — 전역 기본 헤더를 바꾸지 않는다).
"""
import time
import uuid

import regression as rg
from regression import Fail, call, expect_eq, expect_problem, expect_status, items_of, new_key, new_user, \
    new_code, coupon_body, concurrently


# ---------- helpers ----------

def new_tenant():
    return "t-" + uuid.uuid4().hex[:12]


def th(tenant, **extra):
    h = {"X-Tenant-Id": tenant}
    h.update(extra)
    return h


def t_call(tenant, method, path, body=None, headers=None, **kw):
    h = th(tenant)
    h.update(headers or {})
    return call(method, path, body, headers=h, **kw)


def t_product(tenant, price=1000, stock=100):
    r = t_call(tenant, "POST", "/api/products", {"name": f"p-{uuid.uuid4().hex[:8]}", "price": price, "stock": stock})
    return rg.fixture(r, 201, "product create")["id"]


def t_get_product(tenant, pid):
    return rg.fixture(t_call(tenant, "GET", f"/api/products/{pid}"), 200, "product get")


def t_coupon(tenant, **kw):
    return rg.fixture(t_call(tenant, "POST", "/api/coupons", coupon_body(**kw)), 201, "coupon create")


def t_get_coupon(tenant, code):
    return rg.fixture(t_call(tenant, "GET", f"/api/coupons/{code}"), 200, "coupon get")


def t_create(tenant, items, user=None, coupon_code=None, key=None):
    body = {"items": items}
    if coupon_code is not None:
        body["couponCode"] = coupon_code
    return t_call(tenant, "POST", "/api/orders", body,
                  headers={"X-User-Id": user or new_user(), "Idempotency-Key": key or new_key()})


def t_order(tenant, items, user=None, coupon_code=None):
    return rg.fixture(t_create(tenant, items, user, coupon_code), 201, "order create")


def t_get_order(tenant, oid):
    return rg.fixture(t_call(tenant, "GET", f"/api/orders/{oid}"), 200, "order get")


def t_pay(tenant, oid, token="tok_ok", key=None):
    return t_call(tenant, "POST", f"/api/orders/{oid}/pay", {"cardToken": token},
                  headers={"Idempotency-Key": key or new_key()})


def t_paid(tenant, items, user=None):
    o = t_order(tenant, items, user)
    return rg.fixture(t_pay(tenant, o["id"]), 200, "pay")


def t_action(tenant, oid, name):
    return t_call(tenant, "POST", f"/api/orders/{oid}/{name}")


def t_list(tenant, **params):
    from urllib.parse import urlencode
    return t_call(tenant, "GET", "/api/orders?" + urlencode(params))


# ---------- M1 테넌트 헤더 ----------

def m1_missing_header_400():
    """M1.1 M1.2"""
    t = new_tenant()
    pid = t_product(t)
    code = t_coupon(t)["code"]
    oid = t_order(t, items_of((pid, 1)))["id"]
    no = {"X-Tenant-Id": None}
    requests = [
        ("POST products", "POST", "/api/products", {"name": "x", "price": 100, "stock": 1}, {}),
        ("GET product", "GET", f"/api/products/{pid}", None, {}),
        ("POST coupons", "POST", "/api/coupons", coupon_body(), {}),
        ("GET coupon", "GET", f"/api/coupons/{code}", None, {}),
        ("POST orders", "POST", "/api/orders", {"items": items_of((pid, 1))},
         {"X-User-Id": new_user(), "Idempotency-Key": new_key()}),
        ("GET order", "GET", f"/api/orders/{oid}", None, {}),
        ("GET orders", "GET", "/api/orders", None, {}),
        ("pay", "POST", f"/api/orders/{oid}/pay", {"cardToken": "tok_ok"}, {"Idempotency-Key": new_key()}),
        ("cancel", "POST", f"/api/orders/{oid}/cancel", None, {}),
        ("ship", "POST", f"/api/orders/{oid}/ship", None, {}),
        ("deliver", "POST", f"/api/orders/{oid}/deliver", None, {}),
    ]
    for what, method, path, body, extra in requests:
        expect_problem(call(method, path, body, headers={**no, **extra}), 400, f"{what} without tenant",
                       "VALIDATION_ERROR")
    expect_eq(t_get_order(t, oid)["status"], "PENDING_PAYMENT", "order untouched", "atomicity")
    rg.check_product(t_get_product(t, pid), "product", reserved=1)


def m1_invalid_format_400():
    """M1.1 M1.2"""
    for bad in ("ACME", "a_b", "a b", "a" * 31, ""):
        r = call("POST", "/api/products", {"name": "x", "price": 100, "stock": 1}, headers={"X-Tenant-Id": bad})
        expect_problem(r, 400, f"tenant {bad!r}", "VALIDATION_ERROR")
    for ok in ("a", "a-1", "z" * 30):
        r = call("POST", "/api/products", {"name": "x", "price": 100, "stock": 1}, headers={"X-Tenant-Id": ok})
        expect_status(r, 201, f"tenant {ok!r}")


def m1_precedence():
    """M1.2 C3"""
    r = call("POST", "/api/orders", {"items": items_of((rg.missing_id("product"), 1))},
             headers={"X-Tenant-Id": None, "X-User-Id": new_user(), "Idempotency-Key": new_key()})
    expect_problem(r, 400, "missing tenant before 404", "VALIDATION_ERROR")
    expect_problem(call("GET", f"/api/orders/{rg.missing_id('order')}", headers={"X-Tenant-Id": "BAD_ID"}), 400,
                   "bad tenant before 404", "VALIDATION_ERROR")


# ---------- M2 데이터 격리 ----------

def m2_get_cross_tenant_404():
    """M2.1 M2.2"""
    a, b = new_tenant(), new_tenant()
    pid = t_product(a)
    code = t_coupon(a)["code"]
    oid = t_order(a, items_of((pid, 1)))["id"]
    expect_problem(t_call(b, "GET", f"/api/products/{pid}"), 404, "product via other tenant", "PRODUCT_NOT_FOUND")
    expect_problem(t_call(b, "GET", f"/api/coupons/{code}"), 404, "coupon via other tenant", "COUPON_NOT_FOUND")
    expect_problem(t_call(b, "GET", f"/api/orders/{oid}"), 404, "order via other tenant", "ORDER_NOT_FOUND")
    expect_status(t_call(a, "GET", f"/api/orders/{oid}"), 200, "own tenant still sees order")


def m2_actions_cross_tenant_404():
    """M2.2"""
    a, b = new_tenant(), new_tenant()
    pid = t_product(a, stock=10)
    pending = t_order(a, items_of((pid, 2)))
    expect_problem(t_pay(b, pending["id"]), 404, "pay other tenant", "ORDER_NOT_FOUND")
    expect_problem(t_action(b, pending["id"], "cancel"), 404, "cancel other tenant", "ORDER_NOT_FOUND")
    expect_eq(rg.pg_calls(pending["id"]), [], "PG not called", "pg")
    expect_eq(t_get_order(a, pending["id"])["status"], "PENDING_PAYMENT", "pending untouched", "atomicity")
    paid = t_paid(a, items_of((pid, 1)))
    expect_problem(t_action(b, paid["id"], "ship"), 404, "ship other tenant", "ORDER_NOT_FOUND")
    expect_problem(t_action(b, paid["id"], "cancel"), 404, "refund other tenant", "ORDER_NOT_FOUND")
    expect_eq(t_get_order(a, paid["id"])["status"], "PAID", "paid untouched", "atomicity")
    expect_status(t_action(a, paid["id"], "ship"), 200, "ship own")
    expect_problem(t_action(b, paid["id"], "deliver"), 404, "deliver other tenant", "ORDER_NOT_FOUND")
    expect_eq(t_get_order(a, paid["id"])["status"], "SHIPPED", "shipped untouched", "atomicity")
    rg.check_product(t_get_product(a, pid), "product", stock=9, reserved=2)


def m2_order_with_foreign_refs_404():
    """M2.3"""
    a, b = new_tenant(), new_tenant()
    pid = t_product(a, stock=10)
    code = t_coupon(a)["code"]
    expect_problem(t_create(b, items_of((pid, 1))), 404, "foreign product", "PRODUCT_NOT_FOUND")
    own = t_product(b, stock=10)
    expect_problem(t_create(b, items_of((own, 1)), coupon_code=code), 404, "foreign coupon", "COUPON_NOT_FOUND")
    rg.check_product(t_get_product(a, pid), "foreign product", reserved=0)
    rg.check_product(t_get_product(b, own), "own product", reserved=0)
    expect_eq(t_get_coupon(a, code)["usedCount"], 0, "foreign coupon unused", "atomicity")


def m2_location_resolves():
    """M2.4"""
    t = new_tenant()
    r = t_call(t, "POST", "/api/products", {"name": "x", "price": 100, "stock": 5})
    expect_status(r, 201, "product")
    targets = [r.headers.get("location")]
    r = t_call(t, "POST", "/api/coupons", coupon_body())
    expect_status(r, 201, "coupon")
    targets.append(r.headers.get("location"))
    r = t_create(t, items_of((t_product(t), 1)))
    expect_status(r, 201, "order")
    targets.append(r.headers.get("location"))
    from urllib.parse import urljoin, urlparse
    for loc in targets:
        if not loc:
            raise Fail("field", "Location header missing")
        path = urlparse(urljoin(rg.BASE + "/", loc)).path
        expect_status(t_call(t, "GET", path), 200, f"GET {path} with same tenant")


# ---------- M3 쿠폰 코드 ----------

def m3_same_code_two_tenants():
    """M3.1 M3.2"""
    a, b = new_tenant(), new_tenant()
    code = new_code()
    expect_status(t_call(a, "POST", "/api/coupons", coupon_body(code=code, value=100)), 201, "tenant a")
    expect_status(t_call(b, "POST", "/api/coupons", coupon_body(code=code, value=700)), 201, "tenant b same code")
    expect_problem(t_call(a, "POST", "/api/coupons", coupon_body(code=code)), 409, "duplicate within tenant",
                   "DUPLICATE_COUPON_CODE")
    expect_eq((t_get_coupon(a, code)["value"], t_get_coupon(b, code)["value"]), (100, 700), "each tenant's own coupon")


def m3_independent_counters():
    """M3.2"""
    a, b = new_tenant(), new_tenant()
    code = new_code()
    t_coupon(a, code=code, totalQuantity=1, value=100)
    t_coupon(b, code=code, totalQuantity=1, value=100)
    o = t_order(a, items_of((t_product(a), 1)), coupon_code=code)
    expect_eq(o["discount"], 100, "discount in a")
    expect_eq((t_get_coupon(a, code)["usedCount"], t_get_coupon(b, code)["usedCount"]), (1, 0), "usedCount")
    expect_status(t_create(b, items_of((t_product(b), 1)), coupon_code=code), 201, "b still available")
    expect_problem(t_create(a, items_of((t_product(a), 1)), coupon_code=code), 409, "a exhausted", "COUPON_EXHAUSTED")


# ---------- M4 사용자 ----------

def m4_one_per_user_per_tenant():
    """M4.1 M4.2"""
    a, b = new_tenant(), new_tenant()
    code, user = new_code(), new_user()
    t_coupon(a, code=code)
    t_coupon(b, code=code)
    expect_status(t_create(a, items_of((t_product(a), 1)), user, code), 201, "user in a")
    expect_status(t_create(b, items_of((t_product(b), 1)), user, code), 201, "same user id in b")
    expect_problem(t_create(a, items_of((t_product(a), 1)), user, code), 409, "second use in a",
                   "COUPON_NOT_APPLICABLE")


# ---------- M5 멱등성 ----------

def m5_same_key_two_tenants():
    """M5.1"""
    a, b = new_tenant(), new_tenant()
    key, user = new_key(), new_user()
    ra = t_create(a, items_of((t_product(a), 1)), user, key=key)
    rb = t_create(b, items_of((t_product(b), 1)), user, key=key)
    expect_status(ra, 201, "tenant a")
    expect_status(rb, 201, "tenant b with same key")
    if str(ra.body["id"]) == str(rb.body["id"]):
        raise Fail("idempotency", "tenant b got tenant a's order replayed")
    pay_key = new_key()
    expect_status(t_pay(a, ra.body["id"], key=pay_key), 200, "pay a")
    expect_status(t_pay(b, rb.body["id"], key=pay_key), 200, "pay b with same key")
    expect_eq(t_get_order(b, rb.body["id"])["status"], "PAID", "b paid", "idempotency")


def m5_replay_within_tenant():
    """M5.1 R4.2 R4.3"""
    t = new_tenant()
    pid, key, user = t_product(t), new_key(), new_user()
    first = t_create(t, items_of((pid, 1)), user, key=key)
    expect_status(first, 201, "first")
    again = t_create(t, items_of((pid, 1)), user, key=key)
    expect_status(again, 201, "replay")
    expect_eq(str(again.body.get("id")), str(first.body["id"]), "replayed id", "idempotency")
    expect_problem(t_create(t, items_of((pid, 2)), user, key=key), 422, "mismatch within tenant",
                   "IDEMPOTENCY_KEY_MISMATCH")


# ---------- M6 외부 PG ----------

def m6_pg_key_prefixed():
    """M6.1 R5.3"""
    t = new_tenant()
    o = t_order(t, items_of((t_product(t, price=1500), 2)))
    key = new_key()
    expect_status(t_pay(t, o["id"], key=key), 200, "pay")
    calls = rg.pg_calls(o["id"])
    expect_eq([(c["amount"], c["idempotencyKey"]) for c in calls], [(3000, f"{t}:{key}")], "PG request", "pg")


def m6_retry_same_pg_key():
    """M6.1 R5.3 R4.4"""
    t = new_tenant()
    o, key = t_order(t, items_of((t_product(t), 1))), new_key()
    started = time.time()
    expect_status(t_pay(t, o["id"], "tok_slow", key), 503, "first (PG slow)")
    time.sleep(max(0.0, 5.8 - (time.time() - started)))
    expect_status(t_pay(t, o["id"], "tok_slow", key), 200, "retry with same key")
    expect_eq({c["idempotencyKey"] for c in rg.pg_calls(o["id"])}, {f"{t}:{key}"}, "PG key across retries", "pg")


def m6_same_key_charged_separately():
    """M6.1 M5.1"""
    a, b = new_tenant(), new_tenant()
    key = new_key()
    oa = t_order(a, items_of((t_product(a), 1)))
    ob = t_order(b, items_of((t_product(b), 1)))
    expect_status(t_pay(a, oa["id"], key=key), 200, "pay a")
    expect_status(t_pay(b, ob["id"], key=key), 200, "pay b")
    fresh = [c for c in rg.pg_calls(oa["id"]) + rg.pg_calls(ob["id"]) if not c.get("replayed")]
    expect_eq(len(fresh), 2, "two real PG charges", "pg")


# ---------- M7 목록 ----------

def m7_list_isolation():
    """M7.1"""
    a, b = new_tenant(), new_tenant()
    user = new_user()
    pa, pb = t_product(a, stock=50), t_product(b, stock=50)
    ids_a = {str(t_order(a, items_of((pa, 1)), user)["id"]) for _ in range(3)}
    ids_b = {str(t_order(b, items_of((pb, 1)), user)["id"]) for _ in range(2)}
    r = t_list(a, userId=user, size=100)
    expect_status(r, 200, "list a")
    expect_eq({str(o["id"]) for o in r.body["content"]}, ids_a, "a user filter", "field")
    r = t_list(b, size=100)
    expect_status(r, 200, "list b")
    expect_eq({str(o["id"]) for o in r.body["content"]}, ids_b, "b unfiltered = only b", "field")
    r = t_list(b, status="PENDING_PAYMENT", size=100)
    expect_eq({str(o["id"]) for o in r.body["content"]}, ids_b, "b status filter", "field")


def m7_paging_within_tenant():
    """M7.1 R9.5"""
    a, b = new_tenant(), new_tenant()
    user = new_user()
    pa, pb = t_product(a, stock=50), t_product(b, stock=50)
    ids = []
    for _ in range(5):
        ids.append(str(t_order(a, items_of((pa, 1)), user)["id"]))
        t_order(b, items_of((pb, 1)), user)
        time.sleep(0.01)
    seen, cursor = [], None
    for _ in range(10):
        params = {"userId": user, "size": 2}
        if cursor:
            params["cursor"] = cursor
        r = t_list(a, **params)
        expect_status(r, 200, "page")
        seen += [str(o["id"]) for o in r.body["content"]]
        cursor = r.body["nextCursor"]
        if not cursor:
            break
    expect_eq(seen, list(reversed(ids)), "paged ids", "field")


# ---------- M8 동시성 ----------

def m8_coupon_race_two_tenants():
    """M8.2"""
    for rep in range(rg.REPS):
        a, b = new_tenant(), new_tenant()
        code = new_code()
        t_coupon(a, code=code, totalQuantity=5, value=100)
        t_coupon(b, code=code, totalQuantity=5, value=100)
        pa, pb = t_product(a, stock=100), t_product(b, stock=100)
        rs = concurrently(lambda i: t_create(a if i % 2 else b, items_of((pa if i % 2 else pb, 1)),
                                             coupon_code=code), 30)
        if any(r.status >= 500 for r in rs):
            raise Fail("concurrency", f"rep {rep}: 5xx {sorted(r.status for r in rs)}")
        ok_a = sum(r.status == 201 for i, r in enumerate(rs) if i % 2)
        ok_b = sum(r.status == 201 for i, r in enumerate(rs) if not i % 2)
        expect_eq((ok_a, ok_b), (5, 5), f"rep {rep}: successes per tenant", "concurrency")
        expect_eq((t_get_coupon(a, code)["usedCount"], t_get_coupon(b, code)["usedCount"]), (5, 5),
                  f"rep {rep}: usedCount", "concurrency")


def m8_same_key_concurrent_two_tenants():
    """M8.3"""
    for rep in range(rg.REPS):
        a, b = new_tenant(), new_tenant()
        key, user = new_key(), new_user()
        pa, pb = t_product(a), t_product(b)
        rs = concurrently(lambda i: t_create(a if i else b, items_of((pa if i else pb, 1)), user, key=key), 2)
        expect_eq([r.status for r in rs], [201, 201], f"rep {rep}: statuses", "concurrency")
        if str(rs[0].body["id"]) == str(rs[1].body["id"]):
            raise Fail("concurrency", f"rep {rep}: same order returned to both tenants")


def m8_stock_race_in_tenant():
    """M8.1 R10.1"""
    t = new_tenant()
    pid = t_product(t, stock=10)
    rs = concurrently(lambda i: t_create(t, items_of((pid, 1))), 20)
    expect_eq(sorted(r.status for r in rs), [201] * 10 + [409] * 10, "statuses", "concurrency")
    rg.check_product(t_get_product(t, pid), "product", reserved=10)


# ---------- 명세 해석 (주 지표 밖) ----------

def i_cursor_cross_tenant():
    """해석: 다른 테넌트에서 받은 cursor를 써도 그 테넌트의 주문이 노출되지 않는다 (cursor의 테넌트 귀속은 명세에 없음)"""
    a, b = new_tenant(), new_tenant()
    user = new_user()
    pa = t_product(a, stock=10)
    ids_a = {str(t_order(a, items_of((pa, 1)), user)["id"]) for _ in range(3)}
    r = t_list(a, userId=user, size=1)
    cursor = r.body.get("nextCursor") if isinstance(r.body, dict) else None
    if not cursor:
        raise Fail("field", "no cursor from tenant a")
    r = t_list(b, userId=user, size=10, cursor=cursor)
    if r.status == 200:
        leaked = {str(o["id"]) for o in r.body.get("content", [])} & ids_a
        expect_eq(leaked, set(), "tenant a orders via tenant b cursor", "field")
    elif r.status != 400:
        raise Fail("status", f"expected 200 (no leak) or 400, got {r.status}")


CASES = [
    ("M1", m1_missing_header_400, "spec", False), ("M1", m1_invalid_format_400, "spec", False),
    ("M1", m1_precedence, "spec", False),
    ("M2", m2_get_cross_tenant_404, "spec", False), ("M2", m2_actions_cross_tenant_404, "spec", False),
    ("M2", m2_order_with_foreign_refs_404, "spec", False), ("M2", m2_location_resolves, "spec", False),
    ("M3", m3_same_code_two_tenants, "spec", False), ("M3", m3_independent_counters, "spec", False),
    ("M4", m4_one_per_user_per_tenant, "spec", False),
    ("M5", m5_same_key_two_tenants, "spec", False), ("M5", m5_replay_within_tenant, "spec", False),
    ("M6", m6_pg_key_prefixed, "spec", False), ("M6", m6_retry_same_pg_key, "spec", False),
    ("M6", m6_same_key_charged_separately, "spec", False),
    ("M7", m7_list_isolation, "spec", False), ("M7", m7_paging_within_tenant, "spec", False),
    ("M8", m8_coupon_race_two_tenants, "spec", False), ("M8", m8_same_key_concurrent_two_tenants, "spec", False),
    ("M8", m8_stock_race_in_tenant, "spec", False),
    ("I", i_cursor_cross_tenant, "interp", False),
]
REQUIREMENTS = ["M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8"]
REGRESSION_EXCLUDE = {
    "r5_approve": "PG Idempotency-Key가 클라이언트 키 그대로라고 단언 — M6.1이 `{tenantId}:{key}`로 대체 (m6_pg_key_prefixed가 대신 검증)",
    "r5_retry_same_pg_key": "같은 이유 — m6_retry_same_pg_key가 대신 검증",
}
