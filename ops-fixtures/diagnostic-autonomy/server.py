"""Disposable diagnostic fixture. Fault injection is an external evaluator operation, never an Agent tool."""
import json
import os
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

role = os.environ.get("FIXTURE_ROLE", "api")
dependency = os.environ.get("DEPENDENCY_URL")
mode = "healthy"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path not in ("/health", "/business"):
            return self.reply(404, {"error": "unknown endpoint"})
        if mode == "configuration":
            return self.reply(500, {"error": "configuration schema mismatch"})
        if mode == "application":
            return self.reply(500, {"error": "application fault"})
        if dependency:
            try:
                with urllib.request.urlopen(dependency, timeout=1) as response:
                    if response.status != 200:
                        raise ValueError("dependency unhealthy")
            except Exception:
                return self.reply(500, {"error": "dependency unavailable"})
        self.reply(200, {"status": "accepted", "role": role})

    def do_POST(self):
        global mode
        if self.path != "/__fixture/fault":
            return self.reply(404, {"error": "unknown endpoint"})
        size = int(self.headers.get("Content-Length", "0"))
        if size < 1 or size > 256:
            return self.reply(400, {"error": "bounded injection required"})
        value = json.loads(self.rfile.read(size)).get("mode")
        if value not in ("healthy", "application", "configuration", "oom"):
            return self.reply(400, {"error": "unknown mode"})
        mode = value
        if value == "configuration":
            print("ERROR configuration schema mismatch: expected v2, actual v1", flush=True)
        elif value == "application":
            print("ERROR application request failed", flush=True)
        elif value == "healthy":
            print("INFO current application and configuration checks restored", flush=True)
        else:
            print("ERROR allocating 256 MiB with a configured 64 MiB container limit", flush=True)
            self.reply(200, {"injected": "allocation pressure"})
            _ = bytearray(256 * 1024 * 1024)  # cgroup OOM is independently read from Docker inspect.
            return
        self.reply(200, {"injected": value})


print("INFO diagnostic fixture started", flush=True)
ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
