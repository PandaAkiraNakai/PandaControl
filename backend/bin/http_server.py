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
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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
        pass

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
                                  prompt=data.get("prompt", "")[:200])
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
            out = api.run(
                ["systemctl", "is-active", unit], timeout=5,
            ).strip()
            watch_rows.append({"unit": unit, "state": out})
        return {
            "failed": failed,
            "watch": watch_rows,
            "manageable": cfg.get("manageable", []),
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
        return {"sinks": sinks, "default": default, "error": err or err2}

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

    def _stream_events(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "keep-alive")
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
                        self.wfile.write(b": heartbeat\n\n")
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        break
                    last_beat = now
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            self.broker.unsubscribe(q)

    def _sse_write(self, event: str, data: dict) -> None:
        payload = json.dumps(data, ensure_ascii=False, default=str)
        try:
            self.wfile.write(f"event: {event}\n".encode("utf-8"))
            for line in payload.splitlines() or [""]:
                self.wfile.write(f"data: {line}\n".encode("utf-8"))
            self.wfile.write(b"\n")
            self.wfile.flush()
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
