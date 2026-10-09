#!/usr/bin/env python3
"""채점용 외부 PG 스텁 (feature.md "외부 PG 계약"). 표준 라이브러리만 사용한다.

동작은 cardToken으로 정해진다.
  tok_ok           승인
  tok_decline      거절 (200, status=DECLINED)
  tok_error        500
  tok_slow         5초 지연 후 승인 (앱은 2초에 타임아웃해야 한다)
  tok_refund_fail  승인, 이후 환불 요청에 500
  그 밖            승인 (cardToken 없음은 400)
같은 Idempotency-Key의 결제 요청은 저장된 최초 결과를 즉시 돌려준다(500은 저장하지 않는다).

관리용 (인수 테스트 전용)
  GET  /__admin/calls?orderId=X   받은 요청 목록
  POST /__admin/reset

사용법: pg_stub.py [--port 18090]
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

SLOW_SECONDS = 5.0
LOCK = threading.Lock()
CALLS = []         # {kind, orderId, amount, cardToken, idempotencyKey, paymentId, at}
BY_KEY = {}        # Idempotency-Key -> (status, body)
PAYMENTS = {}      # paymentId -> {orderId, cardToken, amount}
SEQ = [0]


def next_payment_id():
    with LOCK:
        SEQ[0] += 1
        return f"pay_{SEQ[0]:06d}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status, body):
        raw = json.dumps(body).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 앱이 타임아웃으로 먼저 끊은 경우

    def _body(self):
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            raw = b""  # JDK HttpClient 등은 본문을 chunked로 보낸다
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                        pass
                    break
                raw += self.rfile.read(size)
                self.rfile.readline()
        else:
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw) if raw else {}
        except ValueError:
            return {}

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/__admin/calls":
            order_id = parse_qs(u.query).get("orderId", [None])[0]
            with LOCK:
                calls = [c for c in CALLS if order_id is None or str(c.get("orderId")) == order_id]
            return self._send(200, calls)
        self._send(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        body = self._body()  # 경로와 무관하게 본문을 항상 소비한다(keep-alive 연결에 남으면 다음 요청이 깨진다)
        if u.path == "/__admin/reset":
            with LOCK:
                CALLS.clear(); BY_KEY.clear(); PAYMENTS.clear()
            return self._send(200, {"ok": True})
        if u.path == "/v1/payments":
            return self._pay(body)
        parts = u.path.strip("/").split("/")
        if len(parts) == 4 and parts[:2] == ["v1", "payments"] and parts[3] == "refund":
            return self._refund(parts[2])
        self._send(404, {"error": "not found"})

    def _pay(self, body):
        key = self.headers.get("Idempotency-Key")
        token = body.get("cardToken")
        call = {"kind": "payment", "orderId": body.get("orderId"), "amount": body.get("amount"),
                "cardToken": token, "idempotencyKey": key, "at": time.time()}
        with LOCK:
            CALLS.append(call)
            stored = BY_KEY.get(key) if key else None
        if stored:
            call["replayed"] = True
            return self._send(*stored)
        if not token:  # 본문을 못 읽었거나 계약과 다른 요청 — 조용히 승인하지 않는다
            return self._send(400, {"error": "cardToken required"})
        if token == "tok_error":
            return self._send(500, {"error": "internal"})
        if token == "tok_slow":
            time.sleep(SLOW_SECONDS)
        pid = next_payment_id()
        status = "DECLINED" if token == "tok_decline" else "APPROVED"
        result = (200, {"paymentId": pid, "status": status})
        with LOCK:
            PAYMENTS[pid] = {"orderId": body.get("orderId"), "cardToken": token, "amount": body.get("amount")}
            if key:
                BY_KEY[key] = result
        call["paymentId"] = pid
        self._send(*result)

    def _refund(self, pid):
        with LOCK:
            payment = PAYMENTS.get(pid)
            CALLS.append({"kind": "refund", "paymentId": pid,
                          "orderId": payment and payment["orderId"], "at": time.time()})
        if not payment:
            return self._send(404, {"error": "unknown payment"})
        if payment["cardToken"] == "tok_refund_fail":
            return self._send(500, {"error": "internal"})
        self._send(200, {"paymentId": pid, "status": "REFUNDED"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18090)
    a = ap.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    server.daemon_threads = True
    print(f"pg stub on :{a.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
