#!/usr/bin/env python3
"""feature.md 계약을 검증하는 블랙박스 인수 테스트.

feature.md 문구만 근거로 작성한다. 정답 구현에 맞춰 고치지 않는다.
표준 라이브러리만 사용한다.

사용법: python3 test_acceptance.py --base-url http://localhost:18080 [--out result.json]
"""
import argparse
import json
import re
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from urllib import error, request
from urllib.parse import urljoin

BASE = ""
PROBLEM_FIELDS = ("type", "title", "status", "detail")
ORDER_FIELDS = ("id", "status", "totalPrice", "items", "createdAt")
ITEM_FIELDS = ("productId", "quantity", "unitPrice")
PRODUCT_FIELDS = ("id", "name", "price", "stock")


class Fail(Exception):
    """category: status | field | format | atomicity | concurrency"""

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
    def content_type(self):
        return self.headers.get("content-type", "")


def call(method, path, body=None, raw_body=None, url=None):
    target = url or BASE + path
    data = None
    headers = {"Accept": "application/json, application/problem+json"}
    if raw_body is not None:
        data = raw_body.encode()
        headers["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = request.Request(target, data=data, method=method, headers=headers)
    try:
        with request.urlopen(req, timeout=30) as r:
            return Resp(r.status, dict(r.headers), r.read().decode())
    except error.HTTPError as e:
        return Resp(e.code, dict(e.headers), e.read().decode())


# ---------- assertion helpers ----------

def expect_status(resp, expected, what):
    if resp.status != expected:
        raise Fail("status", f"{what}: expected {expected}, got {resp.status} body={resp.raw[:200]}")


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
    s = re.sub(r"(\.\d{6})\d+", r"\1", s)  # 나노초는 마이크로초로 자른다
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        raise Fail("field", f"{what}: not ISO-8601: {value!r}")


def expect_problem(resp, expected_status, what):
    expect_status(resp, expected_status, what)
    if not resp.content_type.startswith("application/problem+json"):
        raise Fail("format", f"{what}: content-type {resp.content_type!r}")
    if not isinstance(resp.body, dict):
        raise Fail("format", f"{what}: body is not JSON object")
    missing = [f for f in PROBLEM_FIELDS if f not in resp.body]
    if missing:
        raise Fail("format", f"{what}: problem missing {missing}")
    if resp.body.get("status") != expected_status:
        raise Fail("format", f"{what}: problem.status={resp.body.get('status')!r}")


def check_product(obj, name, price, stock, what):
    expect_fields(obj, PRODUCT_FIELDS, what)
    expect_eq(obj["name"], name, f"{what}.name")
    expect_int(obj["price"], f"{what}.price")
    expect_eq(obj["price"], price, f"{what}.price")
    expect_int(obj["stock"], f"{what}.stock")
    expect_eq(obj["stock"], stock, f"{what}.stock")


def check_order_shape(obj, what):
    expect_fields(obj, ORDER_FIELDS, what)
    expect_int(obj["totalPrice"], f"{what}.totalPrice")
    if not isinstance(obj["items"], list) or not obj["items"]:
        raise Fail("field", f"{what}.items: expected non-empty list")
    for i, item in enumerate(obj["items"]):
        expect_fields(item, ITEM_FIELDS, f"{what}.items[{i}]")
        expect_int(item["quantity"], f"{what}.items[{i}].quantity")
        expect_int(item["unitPrice"], f"{what}.items[{i}].unitPrice")
    parse_iso(obj["createdAt"], f"{what}.createdAt")


def find_item(order, product_id):
    for item in order["items"]:
        if str(item["productId"]) == str(product_id):
            return item
    raise Fail("field", f"order items missing productId {product_id}")


# ---------- fixtures ----------

def uniq(prefix="p"):
    return f"{prefix}-{uuid.uuid4().hex[:10]}"


def new_product(price=1000, stock=10):
    name = uniq()
    r = call("POST", "/api/products", {"name": name, "price": price, "stock": stock})
    if r.status != 201 or not isinstance(r.body, dict) or "id" not in r.body:
        raise Fail("status", f"fixture product create failed: {r.status} {r.raw[:200]}")
    return r.body["id"]


def stock_of(pid):
    r = call("GET", f"/api/products/{pid}")
    if r.status != 200 or not isinstance(r.body, dict) or "stock" not in r.body:
        raise Fail("status", f"fixture product get failed: {r.status} {r.raw[:200]}")
    return r.body["stock"]


def new_order(items):
    r = call("POST", "/api/orders", {"items": items})
    if r.status != 201 or not isinstance(r.body, dict) or "id" not in r.body:
        raise Fail("status", f"fixture order create failed: {r.status} {r.raw[:200]}")
    return r.body


_SAMPLE_IDS = {}


def missing_id(kind):
    """앱이 실제로 쓰는 id 타입(정수/UUID 등)에 맞춰 존재하지 않는 id를 만든다."""
    if kind not in _SAMPLE_IDS:
        pid = new_product(stock=10)
        _SAMPLE_IDS["product"] = pid
        if kind == "order":
            _SAMPLE_IDS["order"] = new_order([{"productId": pid, "quantity": 1}])["id"]
    sample = _SAMPLE_IDS[kind]
    if isinstance(sample, int) and not isinstance(sample, bool):
        return 987654321
    if isinstance(sample, str) and re.fullmatch(r"\d+", sample):
        return "987654321"
    return str(uuid.uuid4())


# ---------- R1 상품 등록 ----------

def r1_create_201():
    name = uniq()
    r = call("POST", "/api/products", {"name": name, "price": 1500, "stock": 7})
    expect_status(r, 201, "create product")
    if not r.headers.get("location"):
        raise Fail("field", "Location header missing")
    check_product(r.body, name, 1500, 7, "create body")


def r1_location_resolves():
    name = uniq()
    r = call("POST", "/api/products", {"name": name, "price": 2000, "stock": 3})
    expect_status(r, 201, "create product")
    loc = r.headers.get("location")
    if not loc:
        raise Fail("field", "Location header missing")
    g = call("GET", None, url=urljoin(BASE + "/", loc))
    expect_status(g, 200, f"GET Location {loc}")
    check_product(g.body, name, 2000, 3, "Location body")


def r1_stock_zero_201():
    r = call("POST", "/api/products", {"name": uniq(), "price": 100, "stock": 0})
    expect_status(r, 201, "stock=0")


def r1_blank_name_400():
    expect_status(call("POST", "/api/products", {"name": "   ", "price": 100, "stock": 1}), 400, "blank name")


def r1_missing_name_400():
    expect_status(call("POST", "/api/products", {"price": 100, "stock": 1}), 400, "missing name")


def r1_price_zero_400():
    expect_status(call("POST", "/api/products", {"name": uniq(), "price": 0, "stock": 1}), 400, "price=0")


def r1_stock_negative_400():
    expect_status(call("POST", "/api/products", {"name": uniq(), "price": 100, "stock": -1}), 400, "stock=-1")


# ---------- R2 상품 조회 ----------

def r2_get_200():
    name = uniq()
    c = call("POST", "/api/products", {"name": name, "price": 3300, "stock": 5})
    expect_status(c, 201, "create product")
    r = call("GET", f"/api/products/{c.body['id']}")
    expect_status(r, 200, "get product")
    check_product(r.body, name, 3300, 5, "get body")
    expect_eq(str(r.body["id"]), str(c.body["id"]), "id")


def r2_not_found_404():
    expect_status(call("GET", f"/api/products/{missing_id('product')}"), 404, "missing product")


# ---------- R3 주문 생성 ----------

def r3_create_201():
    a, b = new_product(price=1000, stock=10), new_product(price=2500, stock=10)
    r = call("POST", "/api/orders", {"items": [{"productId": a, "quantity": 2}, {"productId": b, "quantity": 3}]})
    expect_status(r, 201, "create order")
    if not r.headers.get("location"):
        raise Fail("field", "Location header missing")
    check_order_shape(r.body, "create body")
    expect_eq(r.body["status"], "ORDERED", "status")
    expect_eq(find_item(r.body, a)["unitPrice"], 1000, "unitPrice a")
    expect_eq(find_item(r.body, b)["quantity"], 3, "quantity b")
    expect_eq(r.body["totalPrice"], 2 * 1000 + 3 * 2500, "totalPrice")


def r3_stock_deducted():
    a, b = new_product(stock=10), new_product(stock=5)
    new_order([{"productId": a, "quantity": 4}, {"productId": b, "quantity": 5}])
    expect_eq(stock_of(a), 6, "stock a after order")
    expect_eq(stock_of(b), 0, "stock b after order")


def r3_empty_items_400():
    expect_status(call("POST", "/api/orders", {"items": []}), 400, "empty items")


def r3_quantity_zero_400():
    a = new_product()
    expect_status(call("POST", "/api/orders", {"items": [{"productId": a, "quantity": 0}]}), 400, "quantity=0")


def r3_duplicate_product_400():
    a = new_product()
    r = call("POST", "/api/orders", {"items": [{"productId": a, "quantity": 1}, {"productId": a, "quantity": 2}]})
    expect_status(r, 400, "duplicate productId")


def r3_unknown_product_404():
    expect_status(call("POST", "/api/orders", {"items": [{"productId": missing_id("product"), "quantity": 1}]}), 404, "unknown product")


def r3_insufficient_409():
    a = new_product(stock=2)
    expect_status(call("POST", "/api/orders", {"items": [{"productId": a, "quantity": 3}]}), 409, "insufficient stock")


def r3_atomic_no_partial():
    a, b = new_product(stock=10), new_product(stock=1)
    r = call("POST", "/api/orders", {"items": [{"productId": a, "quantity": 1}, {"productId": b, "quantity": 2}]})
    expect_status(r, 409, "one item insufficient")
    expect_eq(stock_of(a), 10, "stock a must be untouched", category="atomicity")
    expect_eq(stock_of(b), 1, "stock b must be untouched", category="atomicity")


def r3_precedence_400_over_404():
    r = call("POST", "/api/orders", {"items": [{"productId": missing_id("product"), "quantity": 0}]})
    expect_status(r, 400, "invalid quantity + unknown product")


def r3_precedence_404_over_409():
    b = new_product(stock=1)
    r = call("POST", "/api/orders", {"items": [{"productId": missing_id("product"), "quantity": 1}, {"productId": b, "quantity": 5}]})
    expect_status(r, 404, "unknown product + insufficient stock")


# ---------- R4 주문 조회 ----------

def r4_get_200():
    a = new_product(price=1200, stock=10)
    created = new_order([{"productId": a, "quantity": 3}])
    r = call("GET", f"/api/orders/{created['id']}")
    expect_status(r, 200, "get order")
    check_order_shape(r.body, "get body")
    expect_eq(str(r.body["id"]), str(created["id"]), "id")
    expect_eq(r.body["status"], "ORDERED", "status")
    expect_eq(r.body["totalPrice"], 3600, "totalPrice")
    expect_eq(find_item(r.body, a)["unitPrice"], 1200, "unitPrice")


def r4_not_found_404():
    expect_status(call("GET", f"/api/orders/{missing_id('order')}"), 404, "missing order")


# ---------- R5 주문 취소 ----------

def r5_cancel_200():
    a = new_product(stock=10)
    o = new_order([{"productId": a, "quantity": 2}])
    r = call("POST", f"/api/orders/{o['id']}/cancel")
    expect_status(r, 200, "cancel")
    check_order_shape(r.body, "cancel body")
    expect_eq(r.body["status"], "CANCELLED", "status")
    g = call("GET", f"/api/orders/{o['id']}")
    expect_status(g, 200, "get after cancel")
    expect_eq(g.body.get("status") if isinstance(g.body, dict) else None, "CANCELLED", "status after cancel")


def r5_stock_restored():
    a, b = new_product(stock=10), new_product(stock=4)
    o = new_order([{"productId": a, "quantity": 3}, {"productId": b, "quantity": 4}])
    expect_status(call("POST", f"/api/orders/{o['id']}/cancel"), 200, "cancel")
    expect_eq(stock_of(a), 10, "stock a restored")
    expect_eq(stock_of(b), 4, "stock b restored")


def r5_cancel_twice_409():
    a = new_product(stock=10)
    o = new_order([{"productId": a, "quantity": 1}])
    expect_status(call("POST", f"/api/orders/{o['id']}/cancel"), 200, "first cancel")
    expect_status(call("POST", f"/api/orders/{o['id']}/cancel"), 409, "second cancel")
    expect_eq(stock_of(a), 10, "stock restored only once")


def r5_cancel_not_found_404():
    expect_status(call("POST", f"/api/orders/{missing_id('order')}/cancel"), 404, "cancel missing order")


# ---------- R6 주문 목록 ----------

def expect_page(r, what):
    expect_status(r, 200, what)
    expect_fields(r.body, ("content", "page", "size", "totalElements"), what)
    if not isinstance(r.body["content"], list):
        raise Fail("field", f"{what}: content is not a list")
    expect_int(r.body["page"], f"{what}.page")
    expect_int(r.body["size"], f"{what}.size")
    expect_int(r.body["totalElements"], f"{what}.totalElements")


def r6_defaults():
    a = new_product(stock=10)
    new_order([{"productId": a, "quantity": 1}])
    r = call("GET", "/api/orders")
    expect_page(r, "list defaults")
    expect_eq(r.body["page"], 0, "default page")
    expect_eq(r.body["size"], 20, "default size")
    if not r.body["content"]:
        raise Fail("field", "content empty although orders exist")
    check_order_shape(r.body["content"][0], "content[0]")


def r6_sorted_newest_first():
    a = new_product(stock=10)
    ids = []
    for _ in range(3):
        ids.append(str(new_order([{"productId": a, "quantity": 1}])["id"]))
        time.sleep(1.1)  # createdAt 동순위를 피한다
    r = call("GET", "/api/orders?page=0&size=100")
    expect_page(r, "list size=100")
    listed = [str(o.get("id")) for o in r.body["content"] if isinstance(o, dict)]
    positions = [listed.index(i) if i in listed else -1 for i in ids]
    if -1 in positions:
        raise Fail("field", f"created orders not found in first page: {ids}")
    if not positions[2] < positions[1] < positions[0]:
        raise Fail("field", f"not newest-first: positions(oldest→newest)={positions}")


def r6_page_and_size():
    a = new_product(stock=10)
    for _ in range(3):
        new_order([{"productId": a, "quantity": 1}])
    r0 = call("GET", "/api/orders?page=0&size=2")
    expect_page(r0, "page=0,size=2")
    expect_eq(r0.body["size"], 2, "size")
    expect_eq(len(r0.body["content"]), 2, "content length")
    r1 = call("GET", "/api/orders?page=1&size=2")
    expect_page(r1, "page=1,size=2")
    expect_eq(r1.body["page"], 1, "page")
    ids0 = {str(o.get("id")) for o in r0.body["content"]}
    ids1 = {str(o.get("id")) for o in r1.body["content"]}
    if ids0 & ids1:
        raise Fail("field", "page 0 and page 1 overlap")
    if r0.body["totalElements"] < 3:
        raise Fail("field", f"totalElements too small: {r0.body['totalElements']}")


def r6_invalid_params_400():
    for q in ("size=0", "size=101", "page=-1"):
        expect_status(call("GET", f"/api/orders?{q}"), 400, f"list {q}")


# ---------- R7 동시성 ----------

def r7_concurrency():
    for rep in range(1, 6):
        pid = new_product(stock=10)
        barrier = threading.Barrier(20)

        def place(_):
            barrier.wait()
            return call("POST", "/api/orders", {"items": [{"productId": pid, "quantity": 1}]}).status

        with ThreadPoolExecutor(max_workers=20) as ex:
            statuses = list(ex.map(place, range(20)))
        ok, conflict = statuses.count(201), statuses.count(409)
        if ok != 10 or conflict != 10:
            raise Fail("concurrency", f"rep {rep}: 201={ok}, 409={conflict}, others={sorted(set(statuses) - {201, 409})}")
        final = stock_of(pid)
        if final != 0:
            raise Fail("concurrency", f"rep {rep}: final stock {final}")


# ---------- R8 에러 포맷 ----------

def r8_validation_problem():
    expect_problem(call("POST", "/api/products", {"name": "", "price": -1, "stock": 1}), 400, "validation error")


def r8_not_found_problem():
    expect_problem(call("GET", f"/api/products/{missing_id('product')}"), 404, "not found")


def r8_conflict_problem():
    a = new_product(stock=1)
    expect_problem(call("POST", "/api/orders", {"items": [{"productId": a, "quantity": 2}]}), 409, "insufficient stock")


def r8_malformed_json_problem():
    expect_problem(call("POST", "/api/products", raw_body='{"name": "x", "price": '), 400, "malformed JSON")


CASES = [
    ("R1", r1_create_201), ("R1", r1_location_resolves), ("R1", r1_stock_zero_201),
    ("R1", r1_blank_name_400), ("R1", r1_missing_name_400), ("R1", r1_price_zero_400), ("R1", r1_stock_negative_400),
    ("R2", r2_get_200), ("R2", r2_not_found_404),
    ("R3", r3_create_201), ("R3", r3_stock_deducted), ("R3", r3_empty_items_400), ("R3", r3_quantity_zero_400),
    ("R3", r3_duplicate_product_400), ("R3", r3_unknown_product_404), ("R3", r3_insufficient_409),
    ("R3", r3_atomic_no_partial), ("R3", r3_precedence_400_over_404), ("R3", r3_precedence_404_over_409),
    ("R4", r4_get_200), ("R4", r4_not_found_404),
    ("R5", r5_cancel_200), ("R5", r5_stock_restored), ("R5", r5_cancel_twice_409), ("R5", r5_cancel_not_found_404),
    ("R6", r6_defaults), ("R6", r6_sorted_newest_first), ("R6", r6_page_and_size), ("R6", r6_invalid_params_400),
    ("R7", r7_concurrency),
    ("R8", r8_validation_problem), ("R8", r8_not_found_problem), ("R8", r8_conflict_problem), ("R8", r8_malformed_json_problem),
]


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--out")
    ap.add_argument("--only", help="실행할 요구사항 ID, 쉼표 구분 (파일럿용)")
    ap.add_argument("--skip", help="제외할 케이스 함수명, 쉼표 구분 (파일럿용)")
    args = ap.parse_args()
    BASE = args.base_url.rstrip("/")

    only = set(args.only.split(",")) if args.only else None
    skip = set(args.skip.split(",")) if args.skip else set()
    cases = [(r, f) for r, f in CASES if (only is None or r in only) and f.__name__ not in skip]

    results = []
    for req, fn in cases:
        started = time.time()
        try:
            fn()
            results.append({"id": fn.__name__, "req": req, "passed": True})
        except Fail as f:
            results.append({"id": fn.__name__, "req": req, "passed": False, "category": f.category, "message": str(f)})
        except Exception as e:  # 연결 실패·타임아웃 등
            results.append({"id": fn.__name__, "req": req, "passed": False, "category": "error", "message": f"{type(e).__name__}: {e}"})
        results[-1]["seconds"] = round(time.time() - started, 2)

    passed = sum(r["passed"] for r in results)
    by_req = {}
    for r in results:
        by_req.setdefault(r["req"], []).append(r["passed"])
    summary = {
        "passed": passed,
        "total": len(results),
        "pass_rate": round(passed / len(results), 4),
        "requirements_met": sorted(k for k, v in by_req.items() if all(v)),
        "failure_categories": {},
        "cases": results,
    }
    for r in results:
        if not r["passed"]:
            summary["failure_categories"][r["category"]] = summary["failure_categories"].get(r["category"], 0) + 1

    for r in results:
        mark = "PASS" if r["passed"] else f"FAIL[{r['category']}]"
        print(f"{mark:22} {r['req']} {r['id']}" + ("" if r["passed"] else f" — {r['message'][:160]}"))
    print(f"\n{passed}/{len(results)} passed ({summary['pass_rate']:.0%}), requirements met: {summary['requirements_met']}")

    if args.out:
        with open(args.out, "w") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
