"""Synthetic room seed for an owned paused-SDK adoption proof; never accepts a send."""
from collections import Counter
from contextlib import AbstractContextManager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from urllib.parse import unquote, urlsplit

USER = "@hris_22222222222242228222222222222222_11111111111141118111111111111111:proof.invalid"
DEVICE = "HRIS_44444444444444448444444444444444"
ROOM = "!original-queue:proof.invalid"


def seed_route_name(method, path):
    """Fixed endpoint words only; never retain account/room IDs, tokens or query values."""
    method = method if method in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"} else "?"
    if len(path) > 4096:
        return method + " oversized"
    words = {"_matrix", "client", "v1", "v3", "unstable", "versions", "sync", "account",
             "whoami", "capabilities", "keys", "upload", "query", "claim", "room_keys",
             "version", "pushrules", "global", "override", "underride", "content", "room",
             "sender", "dehydrated_device", "org.matrix.msc3814", "devices", "profile",
             "rooms", "members", "joined_members", "context", "messages", "state", "filter",
             "user", "account_data", "send", "join", "sendToDevice", "typing", "read_markers",
             "m.room.encrypted"}
    parts = [unquote(value) for value in urlsplit(path).path.split("/") if value]
    return (method + " /" + "/".join(value if value in words else "*" for value in parts[:12]))[:256]


def room_sync():
    def event(kind, key, content, number):
        return {"type": kind, "state_key": key, "content": content, "sender": USER,
                "event_id": f"$owned-seed-{number}", "origin_server_ts": 1767225600000}
    state = [
        event("m.room.create", "", {"creator": USER, "room_version": "10"}, 1),
        event("m.room.member", USER, {"membership": "join", "displayname": "Owned fixture"}, 2),
        event("m.room.encryption", "", {"algorithm": "m.megolm.v1.aes-sha2"}, 3),
        event("m.room.history_visibility", "", {"history_visibility": "shared"}, 4),
        event("m.room.power_levels", "", {"users": {USER: 100}, "users_default": 0}, 5),
    ]
    return {"next_batch": "owned-seed-1", "account_data": {"events": []},
            "to_device": {"events": []}, "device_lists": {"changed": [], "left": []},
            "device_one_time_keys_count": {"signed_curve25519": 50},
            "rooms": {"join": {ROOM: {"state": {"events": state},
                "timeline": {"events": [], "limited": False, "prev_batch": "owned-seed-0"},
                "ephemeral": {"events": []}, "account_data": {"events": []},
                "unread_notifications": {"notification_count": 0, "highlight_count": 0}}}}}


class OriginalQueueSeed(AbstractContextManager):
    """One loopback listener with bounded requests and explicit joined shutdown."""
    def __init__(self):
        self._lock = threading.Lock()
        self._counts = Counter()
        self._unexpected = Counter()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass  # No headers, tokens, key material or request bodies in diagnostics.

            def setup(self):
                super().setup()
                self.connection.settimeout(5)

            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def do_PUT(self):
                self.respond()

            def respond(self):
                path = urlsplit(self.path).path
                route = (self.command, path)
                replies = {
                    ("GET", "/_matrix/client/versions"): {"versions": ["v1.11"], "unstable_features": {}},
                    ("GET", "/_matrix/client/v3/sync"): room_sync(),
                    ("GET", "/_matrix/client/v3/account/whoami"): {"user_id": USER, "device_id": DEVICE},
                    ("GET", "/_matrix/client/v3/capabilities"): {"capabilities": {}},
                    ("POST", "/_matrix/client/v3/keys/upload"): {"one_time_key_counts": {"signed_curve25519": 50}},
                    ("POST", "/_matrix/client/v3/keys/query"): {"device_keys": {}, "failures": {}},
                }
                parts = [unquote(value) for value in path.split("/")]
                # This synthetic account has no global account-data events. Reads
                # for this exact user get Matrix's missing-event response; writes,
                # other users and malformed event types remain unsupported.
                missing_account_data = (self.command == "GET" and len(parts) == 8
                    and parts[:5] == ["", "_matrix", "client", "v3", "user"]
                    and parts[5] == USER and parts[6] == "account_data"
                    and 0 < len(parts[7]) <= 255
                    and not any(char.isspace() or char == "/" for char in parts[7]))
                supported = route in replies or missing_account_data
                key = seed_route_name(self.command, path) if supported else "unexpected"
                with owner._lock:
                    owner._counts[key] += 1
                    if not supported:
                        name = seed_route_name(self.command, path)
                        if name not in owner._unexpected and len(owner._unexpected) >= 16:
                            name = "other"
                        owner._unexpected[name] += 1
                    total = sum(owner._counts.values())
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    size = -1
                valid = 0 <= size <= 524288 and not self.headers.get("Transfer-Encoding") and total <= 256
                if valid and size:
                    valid = len(self.rfile.read(size)) == size
                status = 200 if valid and route in replies else 400
                value = replies[route] if status == 200 else {"errcode": "M_UNRECOGNIZED", "error": "Owned fixture rejected request"}
                if valid and missing_account_data:
                    status = 404
                    value = {"errcode": "M_NOT_FOUND", "error": "No account data for owned fixture"}
                data = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = False
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._started = False
        self._closed = False

    @property
    def port(self):
        return self._server.server_port

    @property
    def base_url(self):
        return f"http://127.0.0.1:{self.port}/"

    @property
    def counts(self):
        with self._lock:
            return dict(self._counts)

    @property
    def unexpected_routes(self):
        with self._lock:
            return dict(self._unexpected)

    def __enter__(self):
        if self._started or self._closed:
            raise RuntimeError("Owned seed cannot restart")
        self._thread.start()
        self._started = True
        return self

    def __exit__(self, *_args):
        if self._closed:
            return
        if self._started:
            self._server.shutdown()
        self._server.server_close()
        if self._started:
            self._thread.join(timeout=5)
            if self._thread.is_alive():
                raise RuntimeError("Owned seed did not stop")
        self._closed = True
