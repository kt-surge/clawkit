"""Frozen holdout configuration: stock dependency, schema v4/v3, 96 MiB limit.
Injection and hidden truth are evaluator owned; neither is an Agent tool.
"""
import json
import os
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

role = os.environ["FIXTURE_ROLE"]
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
            return self.reply(500, {"error": "configuration schema mismatch v4/v3"})
        if mode == "application":
            return self.reply(500, {"error": "application fault: order calculation unavailable"})
        if dependency:
            try:
                with urllib.request.urlopen(dependency, timeout=1) as response:
                    if response.status != 200:
                        raise ValueError("upstream is unhealthy")
            except Exception:
                return self.reply(500, {"error": "dependency unavailable: stock lookup failed"})
        self.reply(200, {"status": "accepted", "role": role, "revision": "holdout-04"})

    def do_POST(self):
        global mode
        if self.path != "/__fixture/fault":
            return self.reply(404, {"error": "unknown endpoint"})
        size = int(self.headers.get("Content-Length", "0"))
        if size < 1 or size > 256:
            return self.reply(400, {"error": "bounded injection required"})
        value = json.loads(self.rfile.read(size)).get("mode")
        if value not in ("healthy", "application", "configuration", "oom", "exit"):
            return self.reply(400, {"error": "unknown mode"})
        mode = value
        if value == "exit":
            print("ERROR application process terminated after fatal order calculation", flush=True)
            self.reply(200, {"injected": "fatal application exit"})
            threading.Timer(0.1, lambda: os._exit(7)).start()
            return
        if value == "oom":
            print("ERROR allocating 384 MiB above the 96 MiB container limit", flush=True)
            self.reply(200, {"injected": "allocation pressure"})
            _ = bytearray(384 * 1024 * 1024)
            return
        if value == "configuration":
            print("ERROR configuration schema mismatch: expected v4, actual v3", flush=True)
        elif value == "application":
            print("ERROR application order calculation failed", flush=True)
        else:
            print("INFO current checks restored for revision holdout-04", flush=True)
        self.reply(200, {"injected": value})


print("INFO frozen fixture started", flush=True)
ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
