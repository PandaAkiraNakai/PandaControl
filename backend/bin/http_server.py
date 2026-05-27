"""
http_server — REST + SSE para apppanda-backend.

Stack stdlib: http.server.ThreadingHTTPServer + threading. Sin asyncio,
sin aiohttp, sin WebSockets — push se hace con Server-Sent Events.

Recibe un `api` namespace con las refs a las funciones puras y al ctx;
así este módulo no importa del daemon principal y se puede reusar.

Auth: Bearer token en header Authorization. /api/v1/health y
/api/v1/version son públicos (probes).
"""

import hmac
import ipaddress
import json
import mimetypes
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from queue import Empty, Queue
from urllib.parse import parse_qs, unquote, urlsplit


VERSION = "0.1.0"


class EventBroker:
    """Registro thread-safe de suscriptores SSE.

    publish() encola un evento en todas las queues. Si una queue está
    llena (cliente lento), descarta el evento más viejo y sigue — no
    bloquea al monitor.
    """

    QUEUE_MAX = 256

    def __init__(self):
        self._lock = threading.Lock()
        self._subs: set[Queue] = set()

    def subscribe(self) -> Queue:
        q: Queue = Queue(maxsize=self.QUEUE_MAX)
        with self._lock:
            self._subs.add(q)
        return q

    def unsubscribe(self, q: Queue) -> None:
        with self._lock:
            self._subs.discard(q)

    def publish(self, event_type: str, payload: dict) -> None:
        msg = {"type": event_type, "ts": int(time.time()), **payload}
        with self._lock:
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait(msg)
            except Exception:
                try:
                    q.get_nowait()
                    q.put_nowait(msg)
                except Exception:
                    pass

    def subscriber_count(self) -> int:
        with self._lock:
            return len(self._subs)


def _hostname() -> str:
    try:
        return socket.gethostname()
    except OSError:
        return "?"


def _kernel() -> str:
    try:
        with open("/proc/sys/kernel/osrelease") as f:
            return f.read().strip()
    except OSError:
        return "?"


def _uptime_s() -> float:
    try:
        with open("/proc/uptime") as f:
            return float(f.read().split()[0])
    except (OSError, ValueError):
        return 0.0


def _boot_id() -> str:
    try:
        with open("/proc/sys/kernel/random/boot_id") as f:
            return f.read().strip()
    except OSError:
        return ""


# ─── Tailscale identity auth ─────────────────────────────────────────────────

# Rango CGNAT que Tailscale usa para IPs de nodos.
_TS_RANGE_V4 = ipaddress.ip_network("100.64.0.0/10")
_TS_RANGE_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")


def _is_tailscale_ip(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv4Address):
        return ip in _TS_RANGE_V4
    return ip in _TS_RANGE_V6


class TailscaleAuth:
    """Resuelve la identidad del peer Tailscale vía `tailscale whois --json`.
    Cachea resultados para no llamar al binario en cada request."""

    def __init__(self, allowed_logins: list[str], cache_s: int = 60):
        self.allowed_logins = {l.lower() for l in allowed_logins}
        self.cache_s = cache_s
        self._cache: dict[str, tuple[float, str | None]] = {}
        self._lock = threading.Lock()

    def _whois(self, addr: str) -> str | None:
        try:
            proc = subprocess.run(
                ["tailscale", "whois", "--json", addr],
                capture_output=True, text=True, timeout=3,
            )
        except (subprocess.TimeoutExpired, FileNotFoundError):
            return None
        if proc.returncode != 0:
            return None
        try:
            data = json.loads(proc.stdout)
        except json.JSONDecodeError:
            return None
        return data.get("UserProfile", {}).get("LoginName")

    def login_for(self, addr: str) -> str | None:
        now = time.monotonic()
        with self._lock:
            entry = self._cache.get(addr)
            if entry and (now - entry[0]) < self.cache_s:
                return entry[1]
        login = self._whois(addr)
        with self._lock:
            self._cache[addr] = (now, login)
        return login

    def is_allowed(self, addr: str) -> tuple[bool, str | None]:
        if not self.allowed_logins:
            return False, None
        if not _is_tailscale_ip(addr):
            return False, None
        login = self.login_for(addr)
        if not login:
            return False, None
        return (login.lower() in self.allowed_logins), login


class _Handler(BaseHTTPRequestHandler):
    server_version = f"apppanda/{VERSION}"
    sys_version = ""
    # HTTP/1.1 para que el cliente Ktor CIO no rechace responses sin
    # Content-Length (caso típico de SSE). En HTTP/1.0 Ktor tira
    # "Failed to parse request body: request body length should be
    # specified" al no encontrar Content-Length ni Transfer-Encoding.
    protocol_version = "HTTP/1.1"

    api = None
    broker: EventBroker = None
    tokens: list[str] = []
    audit = None
    ts_auth: TailscaleAuth | None = None

    SSE_HEARTBEAT_S = 25

    def log_message(self, fmt, *args):
        sys.stderr.write(
            f"[http] {self.address_string()} - {fmt % args}\n"
        )

    def log_request(self, code='-', size='-'):
        sys.stderr.write(
            f"[http] {self.address_string()} {self.command} "
            f"{self.path} -> {code}\n"
        )

    def _peer_addr(self) -> str:
        # client_address es (host, port); host puede ser IPv4 o IPv6
        return self.client_address[0] if self.client_address else ""

    def _authed_via_tailscale(self) -> tuple[bool, str | None]:
        if self.ts_auth is None:
            return False, None
        return self.ts_auth.is_allowed(self._peer_addr())

    def _authed_via_token(self) -> bool:
        if not self.tokens:
            return False
        h = self.headers.get("Authorization", "")
        if not h.startswith("Bearer "):
            return False
        offered = h[len("Bearer "):].strip()
        for t in self.tokens:
            if hmac.compare_digest(offered, t):
                return True
        return False

    def _authed(self) -> bool:
        ok, _ = self._authed_via_tailscale()
        if ok:
            return True
        return self._authed_via_token()

    def _authed_sudo_internal(self) -> bool:
        """Auth para endpoints internos llamados por sudo-app-askpass desde
        el mismo host. Token configurado en [sudo_app].internal_token."""
        expected = (self.api.ctx.cfg.get("sudo_app", {})
                                       .get("internal_token") or "")
        if not expected:
            return False
        h = self.headers.get("X-Internal-Token", "")
        return bool(h) and hmac.compare_digest(h, expected)

    def _send_json(self, status: int, body, headers: dict | None = None) -> None:
        data = json.dumps(body, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if headers:
            for k, v in headers.items():
                self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _err(self, status: int, msg: str) -> None:
        self._send_json(status, {"error": msg})

    def _audit(self, path: str, status: int) -> None:
        if self.audit is None:
            return
        try:
            self.audit.log(
                "http_req",
                method=self.command,
                path=path,
                status=status,
                peer=self.address_string(),
            )
        except Exception:
            pass

    def _parse_qs(self) -> dict:
        return {k: v[0] for k, v in parse_qs(urlsplit(self.path).query).items()}

    def do_GET(self):  # noqa: N802
        try:
            self._dispatch_get()
        except BrokenPipeError:
            pass
        except Exception:
            traceback.print_exc(file=sys.stderr)
            try:
                self._err(500, "internal error")
            except Exception:
                pass

    def do_POST(self):  # noqa: N802
        try:
            self._dispatch_post()
        except BrokenPipeError:
            pass
        except Exception:
            traceback.print_exc(file=sys.stderr)
            try:
                self._err(500, "internal error")
            except Exception:
                pass

    # ─── Helpers para POST ───────────────────────────────────────────────────

    def _read_json_body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0:
            return {}
        try:
            raw = self.rfile.read(length)
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def _confirm_required(self) -> bool:
        """Acciones destructivas requieren header X-Confirm: true."""
        return self.headers.get("X-Confirm", "").lower() == "true"

    def _dispatch_get(self):
        path = unquote(urlsplit(self.path).path)

        if path == "/api/v1/health":
            self._send_json(200, {"ok": True, "version": VERSION})
            return
        if path == "/api/v1/version":
            self._send_json(200, {
                "version": VERSION,
                "hostname": _hostname(),
                "kernel": _kernel(),
                "boot_id": _boot_id(),
            })
            return

        # sudo wait endpoint: autenticación con token interno (no Tailscale)
        # porque viene del askpass binary corriendo en el mismo host.
        if path.startswith("/api/v1/sudo/") and path.endswith("/wait"):
            self._handle_sudo_wait(path)
            return

        if not self._authed():
            self._err(401, "unauthorized")
            self._audit(path, 401)
            return

        api = self.api

        if path == "/api/v1/status/system":
            body = self._status_system()
        elif path == "/api/v1/status/disk":
            body = {"mounts": api.ctx.metrics.disk()}
        elif path == "/api/v1/status/net":
            body = self._status_net()
        elif path == "/api/v1/status/temps":
            body = {"temps": api.ctx.metrics.temps()}
        elif path == "/api/v1/status/gpu":
            body = {"gpus": api.ctx.metrics.gpus()}
        elif path == "/api/v1/status/smart":
            body = self._status_smart()
        elif path == "/api/v1/processes":
            body = self._processes()
        elif path == "/api/v1/services":
            body = self._services()
        elif path == "/api/v1/logs":
            body = self._logs()
        elif path == "/api/v1/metrics":
            body = self._metrics()
        elif path == "/api/v1/audio/sinks":
            body = self._audio()
        elif path == "/api/v1/clipboard":
            body = self.api.clipboard_get()
        elif path == "/api/v1/screens":
            body = self._screens()
        elif path == "/api/v1/media/players":
            body = self._media_players()
        elif path.startswith("/api/v1/media/") and path.endswith("/status"):
            player = path[len("/api/v1/media/"):-len("/status")]
            body = api.mpris_status(player)
        elif path == "/api/v1/net/neighbors":
            body = self._net_neighbors()
        elif path == "/api/v1/vps":
            body = self._vps_list()
        elif path.startswith("/api/v1/vps/") and path.endswith("/summary"):
            alias = path[len("/api/v1/vps/"):-len("/summary")]
            body = self._vps_summary(alias)
        elif path == "/api/v1/games":
            body = self._games()
        elif path == "/api/v1/apps":
            body = self._apps()
        elif path == "/api/v1/updates":
            body = self._updates()
        elif path == "/api/v1/themes":
            body = self._themes()
        elif path == "/api/v1/themes/image":
            self._themes_image()
            return
        elif path == "/api/v1/files":
            body = self._files_list()
        elif path == "/api/v1/files/download":
            self._files_download()
            return
        elif path == "/api/v1/events":
            self._stream_events()
            return
        else:
            self._err(404, "not found")
            self._audit(path, 404)
            return

        self._send_json(200, body)
        self._audit(path, 200)

    # ─── Dispatch POST ───────────────────────────────────────────────────────

    _CONFIRM_REQUIRED_PREFIXES = (
        "/api/v1/power/",
        "/api/v1/services/",
        "/api/v1/processes/",
        "/api/v1/updates/apply",
    )

    def _dispatch_post(self):
        path = unquote(urlsplit(self.path).path)

        if not self._authed():
            self._err(401, "unauthorized")
            self._audit(path, 401)
            return

        if any(path.startswith(p) for p in self._CONFIRM_REQUIRED_PREFIXES):
            if not self._confirm_required():
                self._err(428, "X-Confirm: true header required for this action")
                self._audit(path, 428)
                return

        # Stream de mouse: body de streaming (chunked), se procesa línea a línea
        # en vivo. No pasa por la lógica normal de _read_json_body.
        if path == "/api/v1/input/mouse/stream":
            self._input_mouse_stream()
            return

        api = self.api
        body: dict = {}
        status = 200

        try:
            if path.startswith("/api/v1/power/"):
                action = path[len("/api/v1/power/"):]
                result = api.execute_power(action)
                body = {"action": action, "result": result}
            elif path.startswith("/api/v1/processes/") and path.endswith("/kill"):
                pid = path[len("/api/v1/processes/"):-len("/kill")]
                result = api.execute_kill(pid)
                body = {"pid": pid, "result": result}
            elif path.startswith("/api/v1/services/"):
                rest = path[len("/api/v1/services/"):]
                parts = rest.rsplit("/", 1)
                if len(parts) != 2:
                    self._err(400, "expected /services/<unit>/<action>")
                    self._audit(path, 400)
                    return
                unit, action = parts
                result = api.execute_svc(unit, action, api.ctx.cfg)
                body = {"unit": unit, "action": action, "result": result}
            elif path == "/api/v1/audio/sink":
                data = self._read_json_body()
                sink = (data.get("sink") or "").strip()
                if not sink:
                    self._err(400, "body needs {sink: ...}")
                    self._audit(path, 400)
                    return
                result = api.audio_set_sink(sink)
                body = {"sink": sink, "result": result}
            elif path == "/api/v1/audio/volume":
                data = self._read_json_body()
                pct = data.get("pct")
                if pct is None:
                    self._err(400, "body needs {pct: 0..150}")
                    self._audit(path, 400)
                    return
                result = api.audio_set_volume(pct)
                body = {"pct": pct, "result": result}
            elif path == "/api/v1/audio/mute":
                data = self._read_json_body()
                state = (data.get("state") or "toggle").strip()
                result = api.audio_set_mute(state)
                body = {"state": state, "result": result}
            elif path == "/api/v1/clipboard":
                data = self._read_json_body()
                text = data.get("text")
                if text is None:
                    self._err(400, "body needs {text: ...}")
                    self._audit(path, 400)
                    return
                result = api.clipboard_set(text)
                body = {"chars": len(text), "result": result}
            elif path.startswith("/api/v1/niri/cmd/"):
                cmd = path[len("/api/v1/niri/cmd/"):]
                output = self._parse_qs().get("output") or None
                result = api.niri_cmd(cmd, output)
                body = {"cmd": cmd, "output": output, "result": result}
            elif path.startswith("/api/v1/screens/dpms/"):
                action = path[len("/api/v1/screens/dpms/"):]
                if action not in ("on", "off"):
                    self._err(400, "action must be on|off")
                    self._audit(path, 400)
                    return
                result = api.niri_dpms(action == "on")
                body = {"action": action, "result": result}
            elif path.startswith("/api/v1/screens/"):
                rest = path[len("/api/v1/screens/"):]
                parts = rest.rsplit("/", 1)
                if len(parts) != 2 or parts[1] not in ("on", "off"):
                    self._err(400, "expected /screens/<output>/<on|off>")
                    self._audit(path, 400)
                    return
                output, action = parts
                result = api.niri_set_output(output, action == "on")
                body = {"output": output, "action": action, "result": result}
            elif path.startswith("/api/v1/media/"):
                rest = path[len("/api/v1/media/"):]
                parts = rest.rsplit("/", 1)
                if len(parts) != 2:
                    self._err(400, "expected /media/<player>/<action>")
                    self._audit(path, 400)
                    return
                player, action = parts
                if action == "fullscreen":
                    # fullscreen del VIDEO (no de la ventana): foco + tecla F
                    result = api.player_video_fullscreen(player)
                else:
                    result = api.mpris_action(action, player)
                body = {"player": player, "action": action, "result": result}
            elif path == "/api/v1/input/mouse/move":
                data = self._read_json_body()
                try:
                    dx = int(float(data.get("dx", 0)))
                    dy = int(float(data.get("dy", 0)))
                except (TypeError, ValueError):
                    self._err(400, "body needs {dx, dy}")
                    self._audit(path, 400)
                    return
                result = api.input_mouse_move(dx, dy)
                body = {"dx": dx, "dy": dy, "result": result}
            elif path == "/api/v1/input/mouse/click":
                data = self._read_json_body()
                button = (data.get("button") or "left").strip()
                result = api.input_mouse_click(button)
                body = {"button": button, "result": result}
            elif path == "/api/v1/input/mouse/scroll":
                data = self._read_json_body()
                direction = (data.get("direction") or "").strip()
                if not direction:
                    self._err(400, "body needs {direction}")
                    self._audit(path, 400)
                    return
                result = api.input_mouse_scroll(direction)
                body = {"direction": direction, "result": result}
            elif path == "/api/v1/input/cursor/highlight":
                # Agranda el cursor unos segundos para ubicarlo (útil en la tele).
                result = api.input_cursor_highlight()
                body = {"result": result}
            elif path == "/api/v1/input/key":
                data = self._read_json_body()
                key = (data.get("key") or "").strip()
                if not key:
                    self._err(400, "body needs {key}")
                    self._audit(path, 400)
                    return
                result = api.input_key_press(key)
                body = {"key": key, "result": result}
            elif path == "/api/v1/input/type":
                data = self._read_json_body()
                text = data.get("text") or ""
                if not text:
                    self._err(400, "body needs {text}")
                    self._audit(path, 400)
                    return
                result = api.input_type_text(text)
                body = {"result": result}
            elif path.startswith("/api/v1/apps/") and path.endswith("/launch"):
                name = path[len("/api/v1/apps/"):-len("/launch")]
                result = api.execute_app(name, api.ctx.cfg)
                body = {"app": name, "result": result}
            elif path.startswith("/api/v1/games/") and path.endswith("/launch"):
                appid = path[len("/api/v1/games/"):-len("/launch")]
                result = api.steam_launch(appid, api.ctx.cfg)
                body = {"appid": appid, "result": result}
            elif path.startswith("/api/v1/net/wake/"):
                alias = path[len("/api/v1/net/wake/"):]
                result = api.wol_send(alias, api.ctx.cfg)
                body = {"alias": alias, "result": result}
            elif path == "/api/v1/updates/apply":
                result = api.execute_apply_updates()
                body = {"result": result}
            elif path == "/api/v1/files/upload":
                body = self._files_upload()
            elif path == "/api/v1/sudo/request":
                # POST del askpass binary: autenticación por token interno
                if not self._authed_sudo_internal():
                    self._err(401, "internal token required")
                    self._audit(path, 401)
                    return
                data = self._read_json_body()
                rid = api.ctx.sudo.new_request(
                    prompt=data.get("prompt", ""),
                    command=data.get("command", ""),
                )
                api.ctx.broker.publish("sudo_request", {
                    "rid": rid,
                    "prompt": data.get("prompt", "")[:200],
                    "command": data.get("command", "")[:300],
                    "hostname": _hostname(),
                    "timeout_s": api.ctx.cfg["sudo_app"]["approval_timeout_s"],
                })
                api.ctx.audit.log("sudo_request", rid=rid,
                                  prompt=data.get("prompt", "")[:200],
                                  command=data.get("command", "")[:300])
                body = {"rid": rid, "status": "pending"}
            elif path.startswith("/api/v1/sudo/") and path.endswith("/decision"):
                rid = path[len("/api/v1/sudo/"):-len("/decision")]
                data = self._read_json_body()
                approved = bool(data.get("approved"))
                ok = api.ctx.sudo.decide(rid, approved)
                api.ctx.audit.log("sudo_decision", rid=rid, approved=approved,
                                  applied=ok)
                if not ok:
                    self._err(404, "request not found or already decided")
                    self._audit(path, 404)
                    return
                body = {"rid": rid, "approved": approved}
            else:
                self._err(404, "not found")
                self._audit(path, 404)
                return

            # Sólo aplica el check 422 si el endpoint devolvió un campo `result`.
            # Endpoints como /sudo/request devuelven {rid, status} sin `result`.
            if isinstance(body, dict) and "result" in body:
                r = (body.get("result") or "").lower()
                if r not in ("ok", "started"):
                    status = 422

        except Exception as e:
            self._send_json(500, {"error": str(e)[:300]})
            self._audit(path, 500)
            return

        self._send_json(status, body)
        self._audit(path, status)

    # ─── Stream de mouse (POST con body chunked, baja latencia) ──────────────

    # Tope de seguridad: sin tráfico en este lapso, se asume cliente muerto.
    # El cliente manda un keepalive "0,0" mucho antes (cada ~14 s).
    _MOUSE_STREAM_IDLE_S = 35

    def _input_mouse_stream(self) -> None:
        """Lee deltas de mouse de un body en streaming y los aplica al instante.
        Cada línea es `dx,dy`. La conexión vive mientras el cliente la mantenga
        abierta (mientras el módulo Control está en primer plano en el celu)."""
        apply_move = self.api.input_mouse_move
        # TCP_NODELAY para que cada delta se procese sin esperar buffering.
        try:
            self.connection.setsockopt(
                socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        # Timeout de lectura: corta si el cliente desaparece sin cerrar (wifi).
        try:
            self.connection.settimeout(self._MOUSE_STREAM_IDLE_S)
        except OSError:
            pass

        self._audit("/api/v1/input/mouse/stream", 200)
        n = 0
        buf = b""
        try:
            for data in self._iter_chunked_body():
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if self._apply_move_line(line, apply_move):
                        n += 1
        except (BrokenPipeError, ConnectionResetError, OSError, socket.timeout):
            pass
        try:
            self.connection.settimeout(None)
        except OSError:
            pass
        try:
            self.api.ctx.audit.log("input_mouse_stream", moves=n)
        except Exception:
            pass
        # El cliente ya cerró su lado; respondemos para cerrar limpio.
        try:
            self._send_json(200, {"result": "ok", "moves": n})
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _iter_chunked_body(self):
        """Itera los datos de un request body con Transfer-Encoding: chunked.
        Si no es chunked, lee hasta Content-Length de una."""
        te = self.headers.get("Transfer-Encoding", "").lower()
        if "chunked" not in te:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            while length > 0:
                chunk = self.rfile.read(min(4096, length))
                if not chunk:
                    return
                length -= len(chunk)
                yield chunk
            return
        while True:
            size_line = self.rfile.readline()
            if not size_line:
                return
            size_hex = size_line.strip().split(b";", 1)[0].strip()
            try:
                size = int(size_hex, 16)
            except ValueError:
                return
            if size == 0:
                self.rfile.readline()  # CRLF final de trailers
                return
            data = self.rfile.read(size)
            self.rfile.readline()  # CRLF que cierra el chunk
            yield data

    @staticmethod
    def _apply_move_line(line: bytes, apply_move) -> bool:
        line = line.strip()
        if not line:
            return False
        try:
            sx, sy = line.split(b",", 1)
            dx, dy = int(sx), int(sy)
        except (ValueError, AttributeError):
            return False
        if dx or dy:
            apply_move(dx, dy)
            return True
        return False

    def _handle_sudo_wait(self, path: str) -> None:
        """Long-poll desde sudo-app-askpass. /api/v1/sudo/{rid}/wait?timeout=60"""
        if not self._authed_sudo_internal():
            self._err(401, "internal token required")
            self._audit(path, 401)
            return
        rid = path[len("/api/v1/sudo/"):-len("/wait")]
        q = self._parse_qs()
        try:
            timeout_s = max(1.0, min(120.0, float(q.get("timeout", "60"))))
        except ValueError:
            timeout_s = 60.0
        entry = self.api.ctx.sudo.wait_for_decision(rid, timeout_s)
        if entry is None:
            self._err(404, "request not found")
            self._audit(path, 404)
            return
        body = {
            "rid": rid,
            "status": entry["status"],
            "decided_at": entry["decided_at"],
        }
        self._send_json(200, body)
        self._audit(path, 200)

    def _status_system(self) -> dict:
        m = self.api.ctx.metrics
        la = m.loadavg()
        mem = m.memory()
        return {
            "hostname": _hostname(),
            "kernel": _kernel(),
            "uptime_s": _uptime_s(),
            "boot_id": _boot_id(),
            "cpu": {"pct": m.cpu_pct(), "cores": m.cpu_count()},
            "load": {"1m": la[0], "5m": la[1], "15m": la[2]},
            "ram": mem,
        }

    def _status_net(self) -> dict:
        api = self.api
        cfg = api.ctx.cfg
        ifs = api.ctx.metrics.net_throughput()
        ping_hosts = cfg.get("network", {}).get("ping_hosts", [])
        pings = []
        for h in ping_hosts:
            out = api.run(["ping", "-c", "1", "-W", "2", h], timeout=5)
            ok = "1 received" in out or "1 packets received" in out
            m = re.search(r"time=([\d.]+) ms", out)
            pings.append({
                "host": h, "ok": ok,
                "rtt_ms": float(m.group(1)) if m else None,
            })
        return {"interfaces": ifs, "pings": pings}

    def _status_smart(self) -> dict:
        api = self.api
        devices = api.ctx.cfg.get("smart", {}).get("devices", [])
        rows = []
        for dev in devices:
            cmd = ["smartctl", "-H", dev]
            if api.ctx.cfg.get("sudo", {}).get("enabled"):
                cmd = ["sudo", "-A"] + cmd
            out = api.run(cmd, timeout=15)
            healthy = "PASSED" in out
            rows.append({"device": dev, "healthy": healthy, "raw": out[:500]})
        return {"devices": rows}

    def _processes(self) -> dict:
        q = self._parse_qs()
        by = q.get("sort", "cpu")
        if by not in ("cpu", "ram"):
            by = "cpu"
        try:
            limit = max(1, min(50, int(q.get("limit", "10"))))
        except ValueError:
            limit = 10
        rows = self.api.top_processes(by, n=limit)
        return {
            "sort": by,
            "rows": [
                {"pid": r[0], "user": r[1], "cpu_pct": r[2],
                 "ram_pct": r[3], "comm": r[4]} for r in rows
            ],
        }

    def _services(self) -> dict:
        api = self.api
        cfg = api.ctx.cfg["services"]
        failed = api.list_failed_services()
        watch_rows = []
        for svc in cfg.get("watch", []):
            unit = svc if "." in svc else f"{svc}.service"
            # Una sola llamada que devuelve LoadState y ActiveState (dos líneas)
            out = api.run([
                "systemctl", "show", "--no-pager", "--value",
                "-p", "LoadState", "-p", "ActiveState", unit,
            ], timeout=5).strip()
            lines = [l.strip() for l in out.splitlines() if l.strip()]
            # Si la unit no existe, systemctl show devuelve LoadState=not-found
            # ActiveState=inactive — la filtramos para no mostrar zombies del
            # config viejo.
            load_state = lines[0] if len(lines) >= 1 else ""
            active_state = lines[1] if len(lines) >= 2 else ""
            if load_state in ("not-found", "masked", ""):
                continue
            watch_rows.append({"unit": unit, "state": active_state})
        # Filtrar también las manageable que no existan
        manageable_filtered = []
        for svc in cfg.get("manageable", []):
            unit = svc if "." in svc else f"{svc}.service"
            out = api.run([
                "systemctl", "show", "--no-pager", "--value",
                "-p", "LoadState", unit,
            ], timeout=5).strip()
            if out and out != "not-found" and out != "masked":
                manageable_filtered.append(svc)
        return {
            "failed": failed,
            "watch": watch_rows,
            "manageable": manageable_filtered,
        }

    def _logs(self) -> dict:
        q = self._parse_qs()
        priority = q.get("priority", "err")
        if not re.fullmatch(r"[a-z]+", priority):
            priority = "err"
        try:
            n = max(1, min(500, int(q.get("n", "30"))))
        except ValueError:
            n = 30
        out = self.api.run(
            ["journalctl", "-p", priority, "-n", str(n),
             "--no-pager", "-o", "short-iso"],
            timeout=10,
        )
        return {"priority": priority, "n": n, "output": out}

    def _metrics(self) -> dict:
        q = self._parse_qs()
        rng = q.get("range", "1h")
        windows = {"1h": 3600, "6h": 6 * 3600, "24h": 24 * 3600}
        secs = windows.get(rng, 3600)
        rows = self.api.ctx.history.fetch(secs)
        return {
            "range": rng,
            "range_s": secs,
            "rows": [
                {"ts": r[0], "cpu_pct": r[1], "ram_pct": r[2],
                 "gpu_pct": r[3], "cpu_temp": r[4], "gpu_temp": r[5],
                 "load1": r[6], "disk_pct": r[7]}
                for r in rows
            ],
        }

    def _audio(self) -> dict:
        sinks, err = self.api.audio_sinks()
        default, err2 = self.api.audio_default_sink()
        return {
            "sinks": sinks,
            "default": default,
            "master": self.api.audio_master(),
            "error": err or err2,
        }

    def _screens(self) -> dict:
        outputs, err = self.api.niri_outputs()
        return {"outputs": outputs, "error": err}

    def _media_players(self) -> dict:
        cfg = self.api.ctx.cfg
        players = self.api.mpris_players(cfg)
        return {"players": players}

    def _net_neighbors(self) -> dict:
        return {
            "gateway": self.api.default_gateway(),
            "neighbors": self.api.lan_neighbors(),
        }

    def _vps_list(self) -> dict:
        cfg = self.api.ctx.cfg.get("vps", {}).get("hosts", {})
        return {
            "hosts": [
                {"alias": k, "ssh_alias": v.get("ssh_alias", k)}
                for k, v in cfg.items()
            ],
        }

    def _vps_summary(self, alias: str) -> dict:
        out, err = self.api.vps_status(self.api.ctx.cfg, alias)
        return {"alias": alias, "output": out, "error": err}

    def _games(self) -> dict:
        cfg = self.api.ctx.cfg
        games = self.api.steam_games(cfg)
        return {
            "use_gamescope": cfg.get("steam", {}).get("use_gamescope", True),
            "games": games,
        }

    def _apps(self) -> dict:
        apps = self.api.ctx.cfg.get("apps", {})
        return {
            "apps": [
                {"name": name, "label": meta.get("label", name),
                 "cmd": meta.get("cmd", [])}
                for name, meta in apps.items()
            ],
        }

    def _updates(self) -> dict:
        cfg = self.api.ctx.cfg.get("updates", {})
        cmd = list(cfg.get("command", ["checkupdates"]))
        out = self.api.run(cmd, timeout=30)
        lines = [l for l in out.splitlines() if l.strip()]
        pkgs = []
        for line in lines:
            parts = line.split()
            if len(parts) >= 4 and parts[2] == "->":
                pkgs.append({
                    "name": parts[0], "from": parts[1], "to": parts[3],
                })
        return {"count": len(pkgs), "packages": pkgs, "raw": out[:4000]}

    # ─── Themes (módulo Temas) ───────────────────────────────────────────
    #
    # Cada *.json del dir de temas define una paleta. Se ignora cualquier
    # archivo que no parsee o al que le falte algún color — así un tema a
    # medias nunca rompe la lista. La app aplica el seleccionado al vuelo.

    _HEX_RE = re.compile(r"^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    _THEME_KEYS = (
        "background", "surface", "surfaceHigh", "onSurface", "onSurfaceMuted",
        "yellow", "magenta", "cyan", "green", "red", "orange",
    )

    def _themes_dir(self) -> Path:
        cfg = self.api.ctx.cfg.get("themes", {})
        raw_dir = cfg.get("dir", "~/.config/apppanda-backend/temas")
        return Path(os.path.expanduser(str(raw_dir)))

    def _themes(self) -> dict:
        base = self._themes_dir()
        themes = []
        if base.is_dir():
            # Temas sueltos en la raíz (sin categoría) + temas dentro de
            # subcarpetas (categoría = nombre de la subcarpeta), para que la app
            # los muestre agrupados. Solo se baja un nivel de profundidad.
            def load_dir(folder: Path, category: str) -> None:
                for entry in sorted(folder.glob("*.json"), key=lambda e: e.name.lower()):
                    if entry.name.startswith("."):
                        continue
                    try:
                        with entry.open("rb") as f:
                            raw = json.load(f)
                    except (OSError, ValueError):
                        continue
                    theme_id = f"{category}/{entry.stem}" if category else entry.stem
                    theme = self._parse_theme(theme_id, raw, folder, category)
                    if theme is not None:
                        themes.append(theme)

            load_dir(base, "")
            for sub in sorted(base.iterdir(), key=lambda e: e.name.lower()):
                if sub.is_dir() and not sub.name.startswith("."):
                    load_dir(sub, sub.name)
        return {"dir": str(base), "themes": themes}

    _FONTS = ("default", "sans", "serif", "mono")
    _ICON_STYLES = ("outlined", "filled", "rounded", "sharp")
    # Efectos de fondo animados que la app sabe dibujar (sin imagen). "" = ninguno.
    _BG_EFFECTS = ("", "matrixRain", "equalizer")

    @staticmethod
    def _clamp_int(v, default: int, lo: int, hi: int) -> int:
        try:
            n = int(v)
        except (TypeError, ValueError):
            return default
        return max(lo, min(hi, n))

    def _parse_theme(self, theme_id: str, raw, folder: Path, category: str = "") -> dict | None:
        if not isinstance(raw, dict):
            return None
        colors_in = raw.get("colors")
        if not isinstance(colors_in, dict):
            return None
        colors = {}
        for k in self._THEME_KEYS:
            v = colors_in.get(k)
            if not isinstance(v, str) or not self._HEX_RE.match(v.strip()):
                return None  # tema incompleto/inválido → se ignora
            colors[k] = v.strip().upper()
        name = raw.get("name")
        if not isinstance(name, str) or not name.strip():
            name = theme_id
        # Campos de "paquete" — fuente, iconos y formas. Valores fuera de
        # rango caen al default en vez de invalidar el tema entero.
        font = raw.get("font", "default")
        if font not in self._FONTS:
            font = "default"
        icon_style = raw.get("iconStyle", "outlined")
        if icon_style not in self._ICON_STYLES:
            icon_style = "outlined"
        # Efecto de fondo animado (lo dibuja la app, no es una imagen). Valor
        # desconocido → sin efecto, no invalida el tema.
        bg_effect = raw.get("backgroundEffect", "")
        if bg_effect not in self._BG_EFFECTS:
            bg_effect = ""
        # Imágenes de fondo opcionales: nombres de archivos en la carpeta de
        # temas. Acepta "backgroundImage" (uno) y/o "backgroundImages" (lista,
        # para elegir entre varios). Solo se reportan los que existan y tengan
        # filename seguro. La app las baja vía /api/v1/themes/image.
        candidates = []
        bg_list = raw.get("backgroundImages")
        if isinstance(bg_list, list):
            candidates.extend(x for x in bg_list if isinstance(x, str))
        bg_one = raw.get("backgroundImage", raw.get("background_image"))
        if isinstance(bg_one, str):
            candidates.append(bg_one)
        images = []
        for c in candidates:
            safe = self._safe_filename(c.strip()) if c.strip() else None
            if safe and (folder / safe).is_file() and safe not in images:
                images.append(safe)
        return {
            "id": theme_id,
            "name": name.strip(),
            "category": category,
            "dark": bool(raw.get("dark", True)),
            "font": font,
            "iconStyle": icon_style,
            "corner": self._clamp_int(raw.get("corner", 12), 12, 0, 48),
            "border": self._clamp_int(raw.get("border", 1), 1, 0, 8),
            "backgroundImage": images[0] if images else "",
            "backgroundImages": images,
            "backgroundEffect": bg_effect,
            "colors": colors,
        }

    def _themes_image(self) -> None:
        """Sirve una imagen de fondo de tema desde la carpeta de temas.
        Mismas defensas que el download de archivos (filename seguro +
        realpath dentro de la carpeta), y solo mime image/*."""
        base = self._themes_dir()
        q = self._parse_qs()
        name = self._safe_filename(q.get("name", ""))
        if name is None or not base.is_dir():
            self._err(400, "name inválido")
            self._audit("/api/v1/themes/image", 400)
            return
        # El tema puede vivir en una subcarpeta (categoría); el nombre llega
        # sin separadores, así que lo buscamos un nivel hacia abajo también.
        path = next((p for p in base.rglob(name) if p.is_file()), None)
        if path is None:
            self._err(404, "no existe")
            self._audit("/api/v1/themes/image", 404)
            return
        try:
            real = path.resolve(strict=True)
            real.relative_to(base.resolve())
        except (ValueError, OSError):
            self._err(403, "fuera de la carpeta de temas")
            self._audit("/api/v1/themes/image", 403)
            return
        ctype, _ = mimetypes.guess_type(name)
        if not ctype or not ctype.startswith("image/"):
            self._err(415, "no es imagen")
            self._audit("/api/v1/themes/image", 415)
            return
        size = path.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        try:
            with path.open("rb") as fp:
                while True:
                    chunk = fp.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        self._audit("/api/v1/themes/image", 200)

    # ─── Files (módulo Archivos) ─────────────────────────────────────────

    _MAX_FILENAME = 255
    _FILENAME_RE = re.compile(r"[A-Za-z0-9._\-+=@\(\) ,'¡!¿?áéíóúñÁÉÍÓÚÑ]+$")

    def _shared_dirs(self) -> list[Path]:
        cfg = self.api.ctx.cfg.get("files", {})
        out: list[Path] = []
        for entry in cfg.get("shared_dirs", []):
            p = Path(os.path.expanduser(str(entry))).resolve()
            if p.is_dir():
                out.append(p)
        return out

    def _resolve_dir(self, idx_str: str) -> Path | None:
        try:
            idx = int(idx_str)
        except (TypeError, ValueError):
            return None
        dirs = self._shared_dirs()
        if 0 <= idx < len(dirs):
            return dirs[idx]
        return None

    def _safe_filename(self, name: str) -> str | None:
        """Devuelve un filename limpio o None si es inválido. Sin separadores,
        sin paths, sin caracteres raros, longitud razonable."""
        if not name or len(name) > self._MAX_FILENAME:
            return None
        if "/" in name or "\\" in name or name in (".", "..") or name.startswith("."):
            return None
        if not self._FILENAME_RE.match(name):
            return None
        return name

    def _files_list(self) -> dict:
        cfg = self.api.ctx.cfg.get("files", {})
        if not cfg.get("enabled", True):
            return {"enabled": False, "dirs": []}
        q = self._parse_qs()
        dirs = self._shared_dirs()
        # Si no se especifica dir, devuelve el listado de shared dirs.
        if "dir" not in q:
            return {
                "enabled": True,
                "upload_to": str(dirs[0]) if dirs else "",
                "max_upload_mb": int(cfg.get("max_upload_mb", 500)),
                "dirs": [
                    {"idx": i, "path": str(p), "label": p.name or str(p)}
                    for i, p in enumerate(dirs)
                ],
            }
        target = self._resolve_dir(q.get("dir", "0"))
        if target is None:
            return {"error": "dir inválido"}
        files = []
        try:
            for entry in sorted(target.iterdir(), key=lambda e: e.name.lower()):
                if entry.name.startswith("."):
                    continue
                try:
                    st = entry.stat()
                except OSError:
                    continue
                files.append({
                    "name": entry.name,
                    "size": st.st_size,
                    "mtime": int(st.st_mtime),
                    "is_dir": entry.is_dir(),
                })
        except PermissionError:
            return {"error": "sin permisos"}
        return {
            "enabled": True,
            "dir_idx": int(q.get("dir", "0")),
            "path": str(target),
            "files": files,
        }

    def _files_download(self) -> None:
        cfg = self.api.ctx.cfg.get("files", {})
        if not cfg.get("enabled", True):
            self._err(403, "files disabled")
            self._audit("/api/v1/files/download", 403)
            return
        q = self._parse_qs()
        target = self._resolve_dir(q.get("dir", ""))
        name = self._safe_filename(q.get("name", ""))
        if target is None or name is None:
            self._err(400, "dir/name inválidos")
            self._audit("/api/v1/files/download", 400)
            return
        path = target / name
        if not path.is_file():
            self._err(404, "no existe")
            self._audit("/api/v1/files/download", 404)
            return
        # Confirmar que el archivo realmente cuelga del shared_dir (defensa
        # contra symlinks que apunten fuera).
        try:
            real = path.resolve(strict=True)
            real.relative_to(target)
        except (ValueError, OSError):
            self._err(403, "fuera del shared_dir")
            self._audit("/api/v1/files/download", 403)
            return
        size = path.stat().st_size
        ctype, _ = mimetypes.guess_type(name)
        self.send_response(200)
        self.send_header("Content-Type", ctype or "application/octet-stream")
        self.send_header("Content-Length", str(size))
        # filename* RFC 5987 (UTF-8) para nombres con tildes/ñ.
        fname_quoted = urllib.parse.quote(name, safe="")
        self.send_header(
            "Content-Disposition",
            f"attachment; filename*=UTF-8''{fname_quoted}",
        )
        self.end_headers()
        try:
            with path.open("rb") as fp:
                while True:
                    chunk = fp.read(64 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        self.api.ctx.audit.log(
            "files_download", dir=str(target), name=name, size=size,
        )

    def _files_upload(self) -> dict:
        cfg = self.api.ctx.cfg.get("files", {})
        if not cfg.get("enabled", True):
            raise PermissionError("files disabled")
        dirs = self._shared_dirs()
        if not dirs:
            raise FileNotFoundError("no hay shared_dirs configurados")
        target_dir = dirs[0]
        # filename viene en header X-Filename (URL-encoded UTF-8).
        raw_name = self.headers.get("X-Filename", "").strip()
        name = self._safe_filename(urllib.parse.unquote(raw_name))
        if name is None:
            return {"result": "error", "error": "X-Filename inválido"}
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        max_bytes = int(cfg.get("max_upload_mb", 500)) * 1024 * 1024
        if length <= 0 or length > max_bytes:
            return {"result": "error",
                    "error": f"size inválido (max {max_bytes // (1024*1024)} MB)"}
        # Si ya existe, agregar sufijo (1), (2), ...
        final_path = target_dir / name
        if final_path.exists():
            stem, dot, ext = name.rpartition(".")
            if dot == "":
                stem, ext = name, ""
            for i in range(1, 1000):
                candidate = target_dir / (
                    f"{stem} ({i}){'.' + ext if ext else ''}"
                )
                if not candidate.exists():
                    final_path = candidate
                    break
        # Escribir streaming. Atómico: tmp + rename.
        tmp_path = final_path.with_suffix(final_path.suffix + ".part")
        remaining = length
        try:
            with tmp_path.open("wb") as fp:
                while remaining > 0:
                    chunk = self.rfile.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    fp.write(chunk)
                    remaining -= len(chunk)
            if remaining > 0:
                tmp_path.unlink(missing_ok=True)
                return {"result": "error", "error": "stream incompleto"}
            tmp_path.rename(final_path)
        except OSError as e:
            tmp_path.unlink(missing_ok=True)
            return {"result": "error", "error": f"escribir: {e}"}
        self.api.ctx.audit.log(
            "files_upload", dir=str(target_dir),
            name=final_path.name, size=length,
        )
        return {
            "result": "ok",
            "saved_as": final_path.name,
            "path": str(final_path),
            "size": length,
        }

    def _stream_events(self) -> None:
        # En HTTP/1.1, una response sin Content-Length DEBE usar
        # Transfer-Encoding: chunked. Sin eso, clientes serios (Ktor
        # CIO entre ellos) rechazan la response con "request body
        # length should be specified" antes de leer ningun byte.
        # TCP_NODELAY desactiva Nagle's algorithm para que los chunks
        # SSE (tipicamente <500 bytes) salgan inmediatamente y no
        # queden buffereados esperando completar un MTU. Sin esto los
        # eventos pueden tardar segundos o no llegar nunca a clientes
        # detras de redes con buffering agresivo (Tailscale, mobile).
        try:
            import socket as _socket
            self.connection.setsockopt(
                _socket.IPPROTO_TCP, _socket.TCP_NODELAY, 1
            )
        except OSError:
            pass
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "keep-alive")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        self._audit("/api/v1/events", 200)

        q = self.broker.subscribe()
        try:
            hello = {
                "type": "hello", "ts": int(time.time()),
                "version": VERSION, "boot_id": _boot_id(),
            }
            self._sse_write("hello", hello)

            # Replay de sudo_requests pendientes al recien conectado.
            # Sin esto, si el cliente reconecta despues del publish,
            # el evento se pierde y la notif fullscreen nunca aparece.
            try:
                api = self.api
                broker = getattr(api.ctx, "sudo", None) if api else None
                if broker:
                    timeout_s = api.ctx.cfg.get(
                        "sudo_app", {}
                    ).get("approval_timeout_s", 60)
                    for entry in broker.pending_list():
                        self._sse_write("sudo_request", {
                            "type": "sudo_request",
                            "rid": entry["rid"],
                            "prompt": entry.get("prompt", ""),
                            "command": entry.get("command", ""),
                            "hostname": _hostname(),
                            "timeout_s": timeout_s,
                        })
            except Exception:
                pass

            last_beat = time.monotonic()

            while True:
                try:
                    msg = q.get(timeout=5)
                    self._sse_write(msg.get("type", "message"), msg)
                except Empty:
                    pass

                now = time.monotonic()
                if now - last_beat >= self.SSE_HEARTBEAT_S:
                    try:
                        self._chunk_write(b": heartbeat\n\n")
                    except (BrokenPipeError, ConnectionResetError):
                        break
                    last_beat = now
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            try:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            self.broker.unsubscribe(q)

    def _chunk_write(self, data: bytes) -> None:
        """Escribe un chunk con framing Transfer-Encoding: chunked."""
        if not data:
            return
        self.wfile.write(f"{len(data):x}\r\n".encode("ascii"))
        self.wfile.write(data)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def _sse_write(self, event: str, data: dict) -> None:
        payload = json.dumps(data, ensure_ascii=False, default=str)
        buf = bytearray()
        buf += f"event: {event}\n".encode("utf-8")
        for line in payload.splitlines() or [""]:
            buf += f"data: {line}\n".encode("utf-8")
        buf += b"\n"
        try:
            self._chunk_write(bytes(buf))
        except (BrokenPipeError, ConnectionResetError):
            raise


def make_token() -> str:
    return secrets.token_hex(32)


def start_http_server(api, broker: EventBroker, *,
                      host: str = "127.0.0.1",
                      port: int = 8889,
                      tokens: list[str] | None = None,
                      audit=None,
                      tailscale_auth: dict | None = None) -> ThreadingHTTPServer:
    ts_auth = None
    if tailscale_auth and tailscale_auth.get("enabled"):
        logins = list(tailscale_auth.get("allowed_logins") or [])
        if logins:
            ts_auth = TailscaleAuth(
                allowed_logins=logins,
                cache_s=int(tailscale_auth.get("whois_cache_s", 60)),
            )
            print(
                f"[http] Tailscale identity auth enabled "
                f"(allowed_logins={logins})",
                file=sys.stderr,
            )
        else:
            print(
                "[http] WARNING: auth.tailscale.enabled=true but "
                "allowed_logins is empty — Tailscale auth disabled",
                file=sys.stderr,
            )

    if not tokens and ts_auth is None:
        print(
            "[http] WARNING: no tokens and no Tailscale auth configured — "
            "all authed endpoints will return 401 "
            "(only /health and /version respond)",
            file=sys.stderr,
        )

    handler_cls = type(
        "AppPandaHandler",
        (_Handler,),
        {
            "api": api,
            "broker": broker,
            "tokens": list(tokens or []),
            "audit": audit,
            "ts_auth": ts_auth,
        },
    )

    server = ThreadingHTTPServer((host, port), handler_cls)
    server.daemon_threads = True
    threading.Thread(
        target=server.serve_forever,
        kwargs={"poll_interval": 0.5},
        daemon=True,
        name="http-server",
    ).start()
    print(
        f"[http] listening on http://{host}:{port} "
        f"(tokens={len(tokens or [])}, ts_auth={ts_auth is not None}, "
        f"version={VERSION})",
        file=sys.stderr,
    )
    return server
