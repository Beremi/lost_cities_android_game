from __future__ import annotations

import argparse
import json
import mimetypes
from pathlib import Path
import socket
import socketserver
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .app import LostCitiesWebApp
from .manifest import ANDROID_ASSET_ROOT, REPO_ROOT


STATIC_ROOT = REPO_ROOT / "web_server" / "static"
DOC_ROOT = REPO_ROOT / "web_server" / "docs"


class DiscoveryResponder(threading.Thread):
    def __init__(self, *, app: LostCitiesWebApp, http_port: int, host: str = "0.0.0.0") -> None:
        super().__init__(daemon=True)
        self.app = app
        self.http_port = http_port
        self.host = host
        self.udp_port = http_port + 1
        self._stop = threading.Event()

    def run(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((self.host, self.udp_port))
            while not self._stop.is_set():
                try:
                    sock.settimeout(0.5)
                    data, address = sock.recvfrom(4096)
                except TimeoutError:
                    continue
                except OSError:
                    break
                if data.decode("utf-8", errors="ignore").strip() != "LOST_CITIES_WEB_DISCOVER":
                    continue
                payload = json.dumps(self.app.server_info(port=self.http_port)).encode("utf-8")
                sock.sendto(payload, address)

    def stop(self) -> None:
        self._stop.set()


class LostCitiesHandler(BaseHTTPRequestHandler):
    server_version = "LostCitiesWeb/1.0"

    @property
    def app(self) -> LostCitiesWebApp:
        return self.server.app  # type: ignore[attr-defined]

    def do_GET(self) -> None:
        self._handle_request("GET")

    def do_POST(self) -> None:
        self._handle_request("POST")

    def do_PUT(self) -> None:
        self._handle_request("PUT")

    def log_message(self, format: str, *args) -> None:
        return

    def _handle_request(self, method: str) -> None:
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            if method == "GET" and path == "/":
                return self._serve_file(STATIC_ROOT / "index.html")
            if method == "GET" and path in {"/app.css", "/app.js"}:
                return self._serve_file(STATIC_ROOT / path.removeprefix("/"))
            if method == "GET" and path.startswith("/static/"):
                return self._serve_file(STATIC_ROOT / path.removeprefix("/static/"))
            if method == "GET" and path.startswith("/assets/"):
                return self._serve_file(ANDROID_ASSET_ROOT / path.removeprefix("/assets/"))
            if method == "GET" and path == "/api/health":
                return self._json({"ok": True})
            if method == "GET" and path in {"/api/server", "/api/server/info"}:
                info = self.app.server_info(port=self.server.server_port)
                payload = {
                    "ok": True,
                    "server": info,
                    "status": "online",
                    "lan_url": f"http://{info['localAddresses'][0]}:{info['port']}" if info["localAddresses"] else "",
                    "public_url": f"http://{info['localAddresses'][0]}:{info['port']}" if info["localAddresses"] else "",
                    "connected_users": [item.to_dict() for item in self.app.users.values()],
                }
                return self._json(payload)
            if method == "GET" and path == "/api/lobby":
                token = _single_query_value(parsed.query, "user_token")
                return self._json(self.app.lobby_snapshot(token))
            if method == "GET" and path == "/api/agents":
                return self._json(self.app.list_agents())
            if method == "GET" and path in {"/api/agents/api-markdown", "/api/docs/agent-api"}:
                return self._serve_file(DOC_ROOT / "agent_api.md", content_type="text/markdown; charset=utf-8")
            if method == "GET" and path.startswith("/api/agents/") and path.endswith("/download"):
                agent_id = path.split("/")[3]
                download_name, body = self.app.download_agent_zip(agent_id)
                headers = {
                    "Content-Type": "application/zip",
                    "Content-Disposition": f'attachment; filename="{download_name}"',
                }
                return self._bytes(body, headers=headers)
            if method == "GET" and path.startswith("/api/matches/"):
                match_id = path.split("/")[3]
                token = _single_query_value(parsed.query, "user_token")
                return self._json(self.app.get_match_view(token=token, match_id=match_id))
            if method == "GET" and path.startswith("/api/replays/"):
                replay_id = path.split("/")[3]
                return self._json(self.app.get_replay(replay_id))
            if method == "GET" and path.startswith("/api/simulations/"):
                simulation_id = path.split("/")[3]
                return self._json(self.app.get_simulation(simulation_id))

            if method == "POST" and path in {"/api/user/connect", "/api/lobby/join"}:
                payload = self._json_body()
                name = str(payload.get("name") or payload.get("player_name") or "")
                return self._json(self.app.connect_user(name))
            if method == "POST" and path == "/api/user/heartbeat":
                payload = self._json_body()
                return self._json(self.app.heartbeat(str(payload.get("userToken", ""))))
            if method == "POST" and path == "/api/challenges/human":
                payload = self._json_body()
                return self._json(
                    self.app.create_human_challenge(
                        token=str(payload.get("userToken", "")),
                        target_user_id=str(payload.get("targetUserId", "")),
                        use_purple=bool(payload.get("usePurple", False)),
                    )
                )
            if method == "POST" and path == "/api/challenges/accept":
                payload = self._json_body()
                return self._json(
                    self.app.accept_human_challenge(
                        token=str(payload.get("userToken", "")),
                        challenge_id=str(payload.get("challengeId", "")),
                    )
                )
            if method == "POST" and path == "/api/matches/human-vs-human":
                payload = self._json_body()
                return self._json(
                    self.app.create_human_match(
                        token=str(payload.get("userToken") or payload.get("user_token") or ""),
                        target_user_id=str(payload.get("targetUserId") or payload.get("opponent_player_id") or ""),
                        use_purple=bool(payload.get("usePurple", False) or (payload.get("rules") or {}).get("usePurple", False)),
                    )
                )
            if method == "POST" and path in {"/api/matches/versus-ai", "/api/matches/human-vs-ai"}:
                payload = self._json_body()
                return self._json(
                    self.app.create_human_vs_ai_match(
                        token=str(payload.get("userToken") or payload.get("user_token") or ""),
                        agent_id=str(payload.get("agentId") or payload.get("agent_id") or ""),
                        use_purple=bool(payload.get("usePurple", False) or (payload.get("rules") or {}).get("usePurple", False)),
                    )
                )
            if method == "POST" and path.startswith("/api/matches/") and path.endswith(("/action", "/actions")):
                match_id = path.split("/")[3]
                payload = self._json_body()
                return self._json(
                    self.app.submit_match_action(
                        token=str(payload.get("userToken") or payload.get("user_token") or ""),
                        match_id=match_id,
                        action=str(payload.get("action", "")),
                        card_id=str(payload.get("cardId") or payload.get("card_id") or ""),
                        suit=payload.get("suit"),
                    )
                )
            if method == "POST" and path in {"/api/replays/ai-match", "/api/replays"}:
                payload = self._json_body()
                return self._json(
                    self.app.create_ai_replay(
                        left_agent_id=str(payload.get("leftAgentId") or payload.get("left_agent_id") or payload.get("agent_a") or ""),
                        right_agent_id=str(payload.get("rightAgentId") or payload.get("right_agent_id") or payload.get("agent_b") or ""),
                        use_purple=bool(payload.get("usePurple", False) or payload.get("use_purple", False)),
                    )
                )
            if method == "POST" and path == "/api/simulations":
                payload = self._json_body()
                return self._json(
                    self.app.create_simulation(
                        left_agent_id=str(payload.get("leftAgentId") or payload.get("left_agent_id") or payload.get("agent_a") or ""),
                        right_agent_id=str(payload.get("rightAgentId") or payload.get("right_agent_id") or payload.get("agent_b") or ""),
                        rounds=int(payload.get("rounds", 1)),
                        use_purple=bool(payload.get("usePurple", False) or payload.get("use_purple", False)),
                    )
                )
            if method == "PUT" and path == "/api/agents/upload":
                file_name = _single_query_value(parsed.query, "filename") or "uploaded-agent.zip"
                body = self._raw_body()
                return self._json(self.app.upload_agent_zip(file_name=file_name, body=body))

            self._json({"ok": False, "error": "Not found."}, status=HTTPStatus.NOT_FOUND)
        except ValueError as exc:
            self._json({"ok": False, "error": str(exc)}, status=HTTPStatus.BAD_REQUEST)
        except Exception as exc:
            self._json({"ok": False, "error": str(exc)}, status=HTTPStatus.INTERNAL_SERVER_ERROR)

    def _json_body(self) -> dict:
        body = self._raw_body()
        if not body:
            return {}
        return json.loads(body.decode("utf-8"))

    def _raw_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def _serve_file(self, path: Path, *, content_type: str | None = None) -> None:
        if not path.exists() or not path.is_file():
            return self._json({"ok": False, "error": "Not found."}, status=HTTPStatus.NOT_FOUND)
        body = path.read_bytes()
        resolved_type = content_type or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        self._bytes(body, headers={"Content-Type": resolved_type})

    def _bytes(self, body: bytes, *, headers: dict[str, str] | None = None, status: HTTPStatus = HTTPStatus.OK) -> None:
        self.send_response(status.value)
        headers = headers or {}
        for key, value in headers.items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload: dict, *, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
        self._bytes(body, headers={"Content-Type": "application/json; charset=utf-8"}, status=status)


def _single_query_value(raw_query: str, key: str) -> str:
    return parse_qs(raw_query).get(key, [""])[0]


class LostCitiesHTTPServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], RequestHandlerClass, app: LostCitiesWebApp):
        super().__init__(server_address, RequestHandlerClass)
        self.app = app


def run_server(*, host: str = "0.0.0.0", port: int = 8743) -> None:
    app = LostCitiesWebApp()
    server = LostCitiesHTTPServer((host, port), LostCitiesHandler, app)
    discovery = DiscoveryResponder(app=app, http_port=port, host=host)
    discovery.start()
    try:
        print(f"Lost Cities web server listening on http://{host}:{port}")
        print(f"Local addresses: {', '.join(app.server_info(port=port)['localAddresses'])}")
        print(f"UDP discovery probe: LOST_CITIES_WEB_DISCOVER -> port {port + 1}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
    finally:
        discovery.stop()
        server.server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the Lost Cities LAN web server.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8743)
    args = parser.parse_args()
    run_server(host=args.host, port=args.port)
