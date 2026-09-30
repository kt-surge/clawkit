"""Disposable evaluation fixture. Injection routes are never tools or inputs of the Agent."""
import json
import os
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROLE = os.environ["FIXTURE_ROLE"]
DEPENDENCY = os.environ.get("DEPENDENCY_URL")
LOCK = threading.Lock()
FAULT_UNTIL = 0.0


def faulted():
    with LOCK:
        return time.monotonic() < FAULT_UNTIL


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, payload):
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path not in ("/health", "/orders"):
            return self.reply(404, {"error": "unknown route"})
        if faulted():
            return self.reply(503, {"error": "service unavailable"})
        if self.path == "/health":
            return self.reply(200, {"status": "UP", "service": ROLE})
        if ROLE != "orders":
            return self.reply(404, {"error": "unknown route"})
        try:
            with urllib.request.urlopen(DEPENDENCY, timeout=1) as response:
                if response.status != 200:
                    raise ValueError("dependency unavailable")
        except (urllib.error.URLError, ValueError, TimeoutError):
            return self.reply(503, {"error": "catalog unavailable"})
        self.reply(200, {"service": "orders", "orderId": "sample-001", "status": "accepted"})

    def do_POST(self):
        global FAULT_UNTIL
        if self.path != "/__fixture/fault":
            return self.reply(404, {"error": "unknown route"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 0 or size > 256:
                raise ValueError("body size")
            payload = json.loads(self.rfile.read(size))
            seconds = int(payload.get("seconds", 3600))
            if seconds < 0 or seconds > 3600:
                raise ValueError("duration")
        except (ValueError, TypeError):
            return self.reply(400, {"error": "invalid injection"})
        with LOCK:
            FAULT_UNTIL = time.monotonic() + seconds
        self.reply(200, {"fixture": "injected"})

    def log_message(self, format, *args):
        # Route bodies and injected truth stay outside the Agent evidence channel.
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
