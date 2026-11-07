"""Run real Swift/native HTTP observation against an owned loopback fixture."""
import argparse
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit, unquote

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_collaboration_sdk import run

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
counts = Counter()
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        with lock:
            counts[path] += 1
        if path.endswith("/versions"):
            status, body = 200, b'{"versions":["v1.11"]}'
        elif "/profile/" in path:
            status, body = 200, b'{"displayname":"Original profile"}'
        else:
            status, body = 404, b'{"errcode":"M_UNRECOGNIZED","error":"fixture"}'
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
env = os.environ.copy()
env.update(MATRIX_OBSERVER_SDK_PATH=str(args.sdk.resolve(strict=True)),
           MATRIX_OBSERVER_FIXTURE_URL=f"http://127.0.0.1:{server.server_port}")
started = time.monotonic()
record = {"passed": False, "actualSwiftNativeHttp": True, "published": False}
try:
    with (output / "swift-test.log").open("w") as log:
        run(["swift", "test", "-c", "release", "--jobs", "2", "--xunit-output",
             str(output / "tests.xml")], cwd=Path(__file__).resolve().parent / "proof",
            env=env, log=log, timeout=900)
    report = ET.parse(output / "tests.xml").getroot()
    cases = list(report.iter("testcase"))
    if len(cases) != 4 or any(list(case) for case in cases):
        raise ValueError("Expected four passing Swift observer cases without skipped tests")
    record["testCases"] = len(cases)
    for user in ("observer", "replacement", "cancelled"):
        matched = sum(count for path, count in counts.items() if path.endswith(f"/@{user}:fixture.invalid"))
        if matched != 1:
            raise ValueError("Unexpected original request count")
    record["passed"] = True
finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
    record.update(elapsedSeconds=round(time.monotonic() - started, 3),
                  requests=dict(counts), fixtureThreadStopped=not thread.is_alive())
    (output / "result.json").write_text(json.dumps(record, indent=2) + "\n")
