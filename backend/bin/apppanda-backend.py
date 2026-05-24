#!/usr/bin/env python3
"""
apppanda-backend — HTTP + SSE server con métricas de la torre.

Diseño:
  · Monitor thread cada `monitor.interval_s` toma snapshot (CPU/RAM/disk/
    temps/GPU/load) y lo guarda en SQLite (WAL). Publica `metric_tick` al
    EventBroker para que los clientes SSE conectados lo reciban en vivo.
  · Detecta servicios que pasan a failed, nuevas sesiones, retorno de
    suspend y boot fresco — los publica como eventos SSE.
  · HTTP server stdlib (http.server.ThreadingHTTPServer) en un thread
    aparte; responde JSON a /api/v1/*. Auth Bearer token.
  · NO tiene integración con Telegram. NO tiene polkit (Fase 1: read-only).
    Acciones destructivas (poder, kill, services) vienen en Fase 3.

Es hermano independiente de `bot-comandos-torre`: si uno cae, el otro
sigue funcionando. Ambos pueden coexistir y muestrean métricas del mismo
sistema en paralelo (cada uno con su propio SQLite).

Stdlib solamente. Opcional: matplotlib (PNG de tendencias — todavía no
expuesto en HTTP; los clientes Android dibujan con bibliotecas nativas).
"""

import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

try:
    import tomllib
except ImportError:
    print("Python 3.11+ required (tomllib missing)", file=sys.stderr)
    sys.exit(2)


CONFIG_PATH = os.environ.get(
    "APPPANDA_CONFIG", "/etc/apppanda-backend/config.toml",
)


# ─── Config ──────────────────────────────────────────────────────────────────

DEFAULT_ALERTS = {
    "cpu_pct":         {"hi": 90.0, "lo": 75.0, "cooldown_s": 600},
    "ram_pct":         {"hi": 90.0, "lo": 80.0, "cooldown_s": 600},
    "disk_pct":        {"hi": 90.0, "lo": 80.0, "cooldown_s": 3600},
    "cpu_temp_c":      {"hi": 85.0, "lo": 75.0, "cooldown_s": 600},
    "gpu_temp_c":      {"hi": 85.0, "lo": 75.0, "cooldown_s": 600},
    "gpu_pct":         {"hi": 95.0, "lo": 80.0, "cooldown_s": 600},
    "load1_per_core":  {"hi": 2.0,  "lo": 1.5,  "cooldown_s": 600},
}


def load_config(path: str) -> dict:
    with open(path, "rb") as f:
        cfg = tomllib.load(f)
    cfg.setdefault("monitor", {})
    cfg["monitor"].setdefault("enabled", True)
    cfg["monitor"].setdefault("interval_s", 60)
    cfg["monitor"].setdefault("tick_publish_s", 5)
    cfg.setdefault("alerts", {})
    for k, v in DEFAULT_ALERTS.items():
        cfg["alerts"].setdefault(k, {})
        for sk, sv in v.items():
            cfg["alerts"][k].setdefault(sk, sv)
    cfg.setdefault("history", {})
    cfg["history"].setdefault(
        "db_path", "/var/lib/apppanda-backend/metrics.db",
    )
    cfg["history"].setdefault("retain_days", 14)
    cfg.setdefault("audit", {})
    cfg["audit"].setdefault(
        "log_path", "/var/log/apppanda-backend/audit.log",
    )
    cfg.setdefault("http", {})
    cfg["http"].setdefault("enabled", True)
    cfg["http"].setdefault("host", "127.0.0.1")
    cfg["http"].setdefault("port", 8890)
    cfg["http"].setdefault("tokens", [])
    cfg.setdefault("auth", {})
    cfg["auth"].setdefault("tailscale", {})
    cfg["auth"]["tailscale"].setdefault("enabled", False)
    cfg["auth"]["tailscale"].setdefault("allowed_logins", [])
    cfg["auth"]["tailscale"].setdefault("whois_cache_s", 60)
    cfg.setdefault("sudo_app", {})
    cfg["sudo_app"].setdefault("enabled", False)
    cfg["sudo_app"].setdefault("internal_token", "")
    cfg["sudo_app"].setdefault("password_file", "/etc/apppanda-backend/sudo-password")
    cfg["sudo_app"].setdefault("fallback_askpass", "/usr/local/bin/sudo-telegram-askpass")
    cfg["sudo_app"].setdefault("approval_timeout_s", 60)
    cfg.setdefault("network", {})
    cfg["network"].setdefault("ping_hosts", ["1.1.1.1"])
    cfg.setdefault("services", {})
    cfg["services"].setdefault("watch", [
        "sshd", "NetworkManager", "systemd-resolved",
        "apppanda-backend", "docker",
    ])
    cfg["services"].setdefault("manageable", [])
    cfg.setdefault("smart", {})
    cfg["smart"].setdefault("devices", [])
    cfg.setdefault("sudo", {})
    cfg["sudo"].setdefault("enabled", False)
    cfg.setdefault("updates", {})
    cfg["updates"].setdefault("command", ["checkupdates"])
    cfg.setdefault("apps", {})
    cfg.setdefault("mpris", {})
    cfg["mpris"].setdefault("ignore_players", [])
    cfg.setdefault("vps", {})
    cfg["vps"].setdefault("hosts", {})
    cfg.setdefault("steam", {})
    cfg["steam"].setdefault("use_gamescope", True)
    cfg.setdefault("files", {})
    cfg["files"].setdefault("enabled", True)
    # Directorios expuestos a la app. Upload va al primero. El usuario puede
    # listar/descargar archivos de cualquiera. ~ se expande al home del usuario
    # que corre el daemon.
    cfg["files"].setdefault("shared_dirs", ["~/Descargas"])
    cfg["files"].setdefault("max_upload_mb", 500)
    cfg["steam"].setdefault("exclude_appids", [
        "228980", "1070560", "1391110", "1493710", "1628350", "4183110",
    ])
    return cfg


# ─── Helpers ─────────────────────────────────────────────────────────────────

def run(cmd: list[str], timeout: int = 10, env: dict | None = None) -> str:
    if env is None:
        env = {**os.environ, "LC_ALL": "C"}
    try:
        return subprocess.check_output(
            cmd, stderr=subprocess.STDOUT, timeout=timeout, text=True, env=env,
        )
    except subprocess.CalledProcessError as e:
        return e.output or f"<exit {e.returncode}>"
    except subprocess.TimeoutExpired:
        return "<timeout>"
    except FileNotFoundError:
        return f"<no encontrado: {cmd[0]}>"


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ─── Audit log (append-only JSONL) ───────────────────────────────────────────

class Audit:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()

    def log(self, event: str, **fields) -> None:
        entry = {"ts": now_iso(), "event": event, **fields}
        line = json.dumps(entry, ensure_ascii=False, default=str) + "\n"
        with self._lock:
            try:
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(line)
            except OSError as e:
                print(f"audit: write failed: {e}", file=sys.stderr)


# ─── Metrics (snapshots de sistema) ──────────────────────────────────────────

class Metrics:
    """Estado entre snapshots para deltas (CPU%, throughput de red)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._cpu_prev: tuple[int, int] | None = None
        self._net_prev: dict[str, tuple[int, int, float]] = {}

    def cpu_pct(self) -> float:
        try:
            with open("/proc/stat") as f:
                parts = f.readline().split()
            vals = [int(x) for x in parts[1:]]
            idle = vals[3] + (vals[4] if len(vals) > 4 else 0)
            total = sum(vals)
        except (OSError, ValueError):
            return 0.0
        with self._lock:
            prev = self._cpu_prev
            self._cpu_prev = (idle, total)
        if prev is None:
            return 0.0
        d_idle = idle - prev[0]
        d_total = total - prev[1]
        if d_total <= 0:
            return 0.0
        return max(0.0, min(100.0, (1 - d_idle / d_total) * 100))

    def cpu_count(self) -> int:
        return os.cpu_count() or 1

    def loadavg(self) -> tuple[float, float, float]:
        try:
            with open("/proc/loadavg") as f:
                a, b, c = f.read().split()[:3]
            return float(a), float(b), float(c)
        except (OSError, ValueError):
            return 0.0, 0.0, 0.0

    def memory(self) -> dict:
        try:
            mi: dict[str, int] = {}
            with open("/proc/meminfo") as f:
                for line in f:
                    k, _, v = line.partition(":")
                    mi[k] = int(v.strip().split()[0])
        except (OSError, ValueError, KeyError):
            return {"total_g": 0.0, "used_g": 0.0, "pct": 0.0}
        total_kb = mi.get("MemTotal", 0)
        avail_kb = mi.get("MemAvailable", mi.get("MemFree", 0))
        used_kb = total_kb - avail_kb
        pct = 100 * used_kb / total_kb if total_kb else 0.0
        return {
            "total_g": total_kb / 1024 / 1024,
            "used_g": used_kb / 1024 / 1024,
            "pct": pct,
        }

    def disk(self) -> list[dict]:
        out = run([
            "df", "-k", "--output=source,size,used,avail,pcent,target",
            "-x", "tmpfs", "-x", "devtmpfs", "-x", "overlay", "-x", "squashfs",
            "-x", "efivarfs", "-x", "fuse.portal", "-x", "nsfs",
        ])
        result = []
        for line in out.strip().splitlines()[1:]:
            parts = line.split()
            if len(parts) < 6:
                continue
            try:
                size_k = int(parts[1])
                used_k = int(parts[2])
            except ValueError:
                continue
            pct_str = parts[4].rstrip("%")
            try:
                pct = float(pct_str)
            except ValueError:
                pct = 0.0
            result.append({
                "source": parts[0],
                "mount": parts[5],
                "size_g": size_k / 1024 / 1024,
                "used_g": used_k / 1024 / 1024,
                "pct": pct,
            })
        return result

    def disk_max_pct(self) -> tuple[str, float]:
        rows = self.disk()
        if not rows:
            return "", 0.0
        worst = max(rows, key=lambda r: r["pct"])
        return worst["mount"], worst["pct"]

    def temps(self) -> list[dict]:
        out = run(["sensors", "-A", "-j"], timeout=5)
        if not out.startswith("<"):
            try:
                data = json.loads(out)
            except json.JSONDecodeError:
                data = None
            if data:
                rows = []
                for chip, content in data.items():
                    if not isinstance(content, dict):
                        continue
                    for label, sensor in content.items():
                        if not isinstance(sensor, dict):
                            continue
                        for k, v in sensor.items():
                            if k.endswith("_input") and isinstance(v, (int, float)):
                                rows.append({
                                    "chip": chip, "label": label, "c": float(v),
                                })
                                break
                if rows:
                    return rows
        rows = []
        for z in sorted(Path("/sys/class/thermal").glob("thermal_zone*")):
            try:
                ttype = (z / "type").read_text().strip()
                temp = int((z / "temp").read_text().strip()) / 1000
                rows.append({"chip": "thermal", "label": ttype, "c": temp})
            except (OSError, ValueError):
                continue
        return rows

    def cpu_temp_max(self) -> float:
        rows = self.temps()
        candidates = []
        for r in rows:
            label = (r["label"] or "").lower()
            chip = (r["chip"] or "").lower()
            if any(k in label for k in (
                "tctl", "tdie", "package id", "core ", "core_", "cpu",
            )) or any(k in chip for k in ("k10temp", "coretemp", "zenpower")):
                candidates.append(r["c"])
        return max(candidates) if candidates else 0.0

    def gpus(self) -> list[dict]:
        result: list[dict] = []
        for card in sorted(Path("/sys/class/drm").glob("card[0-9]")):
            dev = card / "device"
            try:
                vendor = (dev / "vendor").read_text().strip()
            except OSError:
                continue
            if vendor not in ("0x1002", "0x10de", "0x8086"):
                continue
            entry = {
                "name": f"{card.name}",
                "vendor": {"0x1002": "AMD", "0x10de": "NVIDIA", "0x8086": "Intel"}[vendor],
                "pct": None, "mem_pct": None,
                "mem_used_g": None, "mem_total_g": None,
                "temp_c": None, "power_w": None, "fan_rpm": None,
            }
            busy = dev / "gpu_busy_percent"
            if busy.exists():
                try:
                    entry["pct"] = float(busy.read_text().strip())
                except (OSError, ValueError):
                    pass
            mem_used = dev / "mem_info_vram_used"
            mem_total = dev / "mem_info_vram_total"
            if mem_used.exists() and mem_total.exists():
                try:
                    u = int(mem_used.read_text().strip())
                    t = int(mem_total.read_text().strip())
                    entry["mem_used_g"] = u / 1024**3
                    entry["mem_total_g"] = t / 1024**3
                    if t:
                        entry["mem_pct"] = 100 * u / t
                except (OSError, ValueError):
                    pass
            for hwmon in (dev / "hwmon").glob("hwmon*") if (dev / "hwmon").exists() else []:
                for f in hwmon.glob("temp*_input"):
                    try:
                        c = int(f.read_text().strip()) / 1000
                        if entry["temp_c"] is None or c > entry["temp_c"]:
                            entry["temp_c"] = c
                    except (OSError, ValueError):
                        continue
                p = hwmon / "power1_average"
                if p.exists():
                    try:
                        entry["power_w"] = int(p.read_text().strip()) / 1_000_000
                    except (OSError, ValueError):
                        pass
                fan = hwmon / "fan1_input"
                if fan.exists():
                    try:
                        entry["fan_rpm"] = int(fan.read_text().strip())
                    except (OSError, ValueError):
                        pass
            result.append(entry)

        if shutil.which("nvidia-smi") and not any(g["vendor"] == "NVIDIA" and g["pct"] is not None for g in result):
            out = run([
                "nvidia-smi",
                "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
                "--format=csv,noheader,nounits",
            ], timeout=5)
            if not out.startswith("<"):
                for line in out.strip().splitlines():
                    parts = [p.strip() for p in line.split(",")]
                    if len(parts) < 6:
                        continue
                    try:
                        result.append({
                            "name": parts[0], "vendor": "NVIDIA",
                            "pct": float(parts[1]),
                            "mem_used_g": float(parts[2]) / 1024,
                            "mem_total_g": float(parts[3]) / 1024,
                            "mem_pct": 100 * float(parts[2]) / float(parts[3]) if float(parts[3]) else None,
                            "temp_c": float(parts[4]),
                            "power_w": float(parts[5]),
                            "fan_rpm": None,
                        })
                    except ValueError:
                        continue
        return result

    def gpu_max_temp(self) -> float:
        return max((g["temp_c"] for g in self.gpus() if g["temp_c"]), default=0.0)

    def gpu_max_pct(self) -> float:
        return max((g["pct"] for g in self.gpus() if g["pct"]), default=0.0)

    def net_throughput(self) -> dict[str, dict]:
        try:
            with open("/proc/net/dev") as f:
                lines = f.readlines()[2:]
        except OSError:
            return {}
        now = time.monotonic()
        result = {}
        with self._lock:
            for line in lines:
                if ":" not in line:
                    continue
                name, _, rest = line.partition(":")
                name = name.strip()
                if name == "lo":
                    continue
                cols = rest.split()
                rx = int(cols[0])
                tx = int(cols[8])
                prev = self._net_prev.get(name)
                self._net_prev[name] = (rx, tx, now)
                if prev:
                    dt = now - prev[2]
                    if dt > 0:
                        result[name] = {
                            "rx_bps": (rx - prev[0]) / dt,
                            "tx_bps": (tx - prev[1]) / dt,
                            "rx_total": rx, "tx_total": tx,
                        }
                else:
                    result[name] = {
                        "rx_bps": 0.0, "tx_bps": 0.0,
                        "rx_total": rx, "tx_total": tx,
                    }
        return result


# ─── Alertas con histéresis ──────────────────────────────────────────────────

class Alerts:
    """Evalúa umbrales con histéresis y cooldown. Devuelve True cuando hay
    que disparar la alerta (transición desde "armado" cruzando `hi`)."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self._state: dict[str, dict] = {
            k: {"armed": True, "last_fired": 0.0, "snoozed_until": 0.0}
            for k in DEFAULT_ALERTS
        }
        self._lock = threading.Lock()

    def snooze(self, key: str, seconds: int) -> None:
        with self._lock:
            if key in self._state:
                self._state[key]["snoozed_until"] = time.time() + seconds

    def evaluate(self, key: str, value: float) -> bool:
        if key not in self._state:
            return False
        thresh = self.cfg["alerts"].get(key, {})
        hi = float(thresh.get("hi", 0))
        lo = float(thresh.get("lo", 0))
        cooldown = float(thresh.get("cooldown_s", 600))
        now = time.time()
        with self._lock:
            st = self._state[key]
            if now < st["snoozed_until"]:
                if value <= lo:
                    st["armed"] = True
                return False
            if st["armed"] and value >= hi:
                if now - st["last_fired"] >= cooldown:
                    st["last_fired"] = now
                    st["armed"] = False
                    return True
            elif not st["armed"] and value <= lo:
                st["armed"] = True
        return False


# ─── SQLite histórico ────────────────────────────────────────────────────────

class History:
    def __init__(self, path: str, retain_days: int):
        self.path = path
        self.retain_days = retain_days
        self._lock = threading.Lock()
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("""
            CREATE TABLE IF NOT EXISTS metrics (
                ts INTEGER PRIMARY KEY,
                cpu_pct REAL,
                ram_pct REAL,
                gpu_pct REAL,
                cpu_temp REAL,
                gpu_temp REAL,
                load1 REAL,
                disk_pct REAL
            )
        """)
        self._conn.commit()

    def insert(self, ts: int, snap: dict) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT OR REPLACE INTO metrics VALUES (?,?,?,?,?,?,?,?)",
                (
                    ts,
                    snap.get("cpu_pct"), snap.get("ram_pct"),
                    snap.get("gpu_pct"), snap.get("cpu_temp"),
                    snap.get("gpu_temp"), snap.get("load1"),
                    snap.get("disk_pct"),
                ),
            )
            self._conn.commit()

    def prune(self) -> None:
        cutoff = int(time.time()) - self.retain_days * 86400
        with self._lock:
            self._conn.execute("DELETE FROM metrics WHERE ts < ?", (cutoff,))
            self._conn.commit()

    def fetch(self, since_s: int) -> list[tuple]:
        cutoff = int(time.time()) - since_s
        with self._lock:
            cur = self._conn.execute(
                "SELECT ts,cpu_pct,ram_pct,gpu_pct,cpu_temp,gpu_temp,load1,disk_pct "
                "FROM metrics WHERE ts >= ? ORDER BY ts", (cutoff,),
            )
            return cur.fetchall()


# ─── Funciones puras (queries de sistema) ────────────────────────────────────

def list_failed_services() -> list[str]:
    out = run([
        "systemctl", "list-units", "--state=failed",
        "--no-legend", "--no-pager", "--plain",
    ])
    if out.startswith("<"):
        return []
    units = []
    for line in out.strip().splitlines():
        parts = line.split()
        if parts:
            units.append(parts[0])
    return units


def list_sessions() -> set[str]:
    out = run(["loginctl", "list-sessions", "--no-legend", "--no-pager"])
    if out.startswith("<"):
        return set()
    ids = set()
    for line in out.strip().splitlines():
        parts = line.split()
        if parts:
            ids.add(parts[0])
    return ids


def top_processes(by: str, n: int = 10) -> list[list[str]]:
    sort_key = "-pcpu" if by == "cpu" else "-pmem"
    out = run([
        "ps", "-eo", "pid,user:12,pcpu,pmem,comm",
        "--sort", sort_key, "--no-headers",
    ])
    rows = []
    for line in out.strip().splitlines()[:n]:
        parts = line.split(None, 4)
        if len(parts) == 5:
            rows.append(parts)
    return rows


# ─── Niri ────────────────────────────────────────────────────────────────────

def niri_socket_env() -> dict:
    uid = os.getuid()
    candidates = sorted(
        Path(f"/run/user/{uid}").glob("niri.*.sock"),
        key=lambda p: p.stat().st_mtime, reverse=True,
    )
    env = {**os.environ, "LC_ALL": "C"}
    if candidates:
        env["NIRI_SOCKET"] = str(candidates[0])
    return env


def _niri_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    env = niri_socket_env()
    if "NIRI_SOCKET" not in env:
        return -1, "", "no encontré socket de niri (¿está corriendo?)"
    try:
        proc = subprocess.run(
            ["niri", "msg", *args],
            capture_output=True, text=True, timeout=timeout, env=env,
        )
    except subprocess.TimeoutExpired:
        return -1, "", "timeout consultando niri"
    except FileNotFoundError:
        return -1, "", "niri no encontrado"
    return proc.returncode, proc.stdout or "", (proc.stderr or "").strip()


def niri_outputs() -> tuple[list[dict], str | None]:
    rc, stdout, stderr = _niri_run(["-j", "outputs"])
    if rc != 0:
        return [], (stderr or stdout or f"exit {rc}")[:300]
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return [], f"JSON inválido: {e}"
    out = []
    for name, info in sorted(data.items()):
        if not isinstance(info, dict):
            continue
        make = (info.get("make") or "").strip()
        model = (info.get("model") or "").strip()
        label = " ".join(p for p in (make, model) if p) or name
        on = info.get("logical") is not None
        out.append({"name": name, "label": label, "on": on})
    return out, None


def _player_app_id_prefix(player: str) -> str:
    return player.split(".", 1)[0].lower()


def niri_focus_player_window(player: str) -> str:
    """Enfoca la ventana cuyo app_id contiene el prefijo del player MPRIS.
    Ej: player 'brave.instance4662' → busca ventana con app_id que contenga 'brave'.
    """
    rc, stdout, stderr = _niri_run(["-j", "windows"])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    try:
        windows = json.loads(stdout)
    except json.JSONDecodeError as e:
        return f"json: {e}"
    prefix = _player_app_id_prefix(player)
    if not prefix:
        return "player sin prefijo"
    match = next(
        (w for w in windows if prefix in (w.get("app_id") or "").lower()),
        None,
    )
    if not match:
        return f"sin ventana con app_id ~ {prefix}"
    wid = match.get("id")
    if wid is None:
        return "ventana sin id"
    rc, _, stderr = _niri_run(["action", "focus-window", "--id", str(wid)])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


def wtype_key(key: str) -> str:
    """Envía una tecla al compositor vía wtype (zwp_virtual_keyboard_v1)."""
    uid = os.getuid()
    env = {
        **os.environ,
        "XDG_RUNTIME_DIR": f"/run/user/{uid}",
        "WAYLAND_DISPLAY": "wayland-1",
    }
    try:
        proc = subprocess.run(
            ["wtype", key],
            capture_output=True, text=True, timeout=5, env=env,
        )
    except FileNotFoundError:
        return "wtype no instalado (sudo pacman -S wtype)"
    except subprocess.TimeoutExpired:
        return "timeout en wtype"
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()[:200]
    return "ok"


def player_video_fullscreen(player: str) -> str:
    """Fullscreen del video (no de la ventana): enfoca la ventana del player
    y manda la tecla F (atajo estándar de YouTube/Twitch/mpv/Spotify desktop).
    """
    result = niri_focus_player_window(player)
    if result != "ok":
        return f"focus: {result}"
    time.sleep(0.15)
    return wtype_key("f")


# ─── Audio (pactl) ───────────────────────────────────────────────────────────

def _pactl_env() -> dict:
    uid = os.getuid()
    return {
        **os.environ,
        "XDG_RUNTIME_DIR": f"/run/user/{uid}",
        "LC_ALL": "C.UTF-8",
    }


def _pactl_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["pactl", *args],
            capture_output=True, text=True, timeout=timeout, env=_pactl_env(),
        )
    except subprocess.TimeoutExpired:
        return -1, "", "timeout consultando pactl"
    except FileNotFoundError:
        return -1, "", "pactl no encontrado"
    return proc.returncode, proc.stdout or "", (proc.stderr or "").strip()


def audio_sinks() -> tuple[list[dict], str | None]:
    rc, stdout, stderr = _pactl_run(["-f", "json", "list", "sinks"])
    if rc != 0:
        return [], (stderr or stdout or f"exit {rc}")[:300]
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return [], f"JSON inválido: {e}"
    out = []
    for s in data:
        name = s.get("name") or ""
        if not name:
            continue
        props = s.get("properties") or {}
        prof = (props.get("device.profile.name") or "").lower()
        if "hdmi" in prof or "hdmi" in name.lower():
            kind, icon = "hdmi", "📺"
        elif "analog" in prof or "analog" in name.lower():
            kind, icon = "analog", "🎧"
        elif "bluez" in name.lower() or "bluetooth" in prof:
            kind, icon = "bt", "🔵"
        elif "usb" in (props.get("device.bus") or "").lower():
            kind, icon = "usb", "🎚"
        else:
            kind, icon = "other", "🔊"
        label = (
            (props.get("node.nick") or "").strip()
            or (props.get("alsa.name") or "").strip()
            or (props.get("device.description") or "").strip()
            or name
        )
        out.append({"name": name, "label": label, "icon": icon, "kind": kind})
    return out, None


def audio_default_sink() -> tuple[str, str | None]:
    rc, stdout, stderr = _pactl_run(["get-default-sink"])
    if rc != 0:
        return "", (stderr or stdout or f"exit {rc}")[:300]
    return stdout.strip(), None


# ─── MPRIS (playerctl) ───────────────────────────────────────────────────────

def _playerctl_env() -> dict:
    uid = os.getuid()
    return {
        **os.environ,
        "XDG_RUNTIME_DIR": f"/run/user/{uid}",
        "LC_ALL": "C.UTF-8",
    }


def _playerctl_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["playerctl", *args],
            capture_output=True, text=True, timeout=timeout,
            env=_playerctl_env(),
        )
    except subprocess.TimeoutExpired:
        return -1, "", "timeout"
    except FileNotFoundError:
        return -1, "", "playerctl no encontrado"
    return proc.returncode, proc.stdout or "", (proc.stderr or "").strip()


def mpris_players(cfg: dict | None = None) -> list[str]:
    rc, stdout, _ = _playerctl_run(["-l"])
    if rc != 0:
        return []
    ignore = set((cfg or {}).get("mpris", {}).get("ignore_players") or [])
    return [
        p.strip() for p in stdout.splitlines()
        if p.strip() and p.strip() not in ignore
    ]


def mpris_status(player: str) -> dict:
    args = ["-p", player]
    out: dict = {"status": "?", "title": "", "artist": "", "album": ""}
    rc, stdout, _ = _playerctl_run([*args, "status"])
    if rc == 0:
        out["status"] = stdout.strip()
    for field in ("title", "artist", "album"):
        rc, stdout, _ = _playerctl_run([*args, "metadata", f"xesam:{field}"])
        if rc == 0:
            out[field] = stdout.strip()
    return out


# ─── Red ─────────────────────────────────────────────────────────────────────

def lan_neighbors() -> list[dict]:
    out = run(["ip", "-4", "neigh", "show"], timeout=5)
    rows = []
    for line in out.strip().splitlines():
        parts = line.split()
        if len(parts) < 4:
            continue
        ip = parts[0]
        dev = parts[parts.index("dev") + 1] if "dev" in parts else "?"
        mac = parts[parts.index("lladdr") + 1] if "lladdr" in parts else ""
        state = parts[-1]
        rows.append({"ip": ip, "mac": mac, "dev": dev, "state": state})
    return rows


def default_gateway() -> str:
    out = run(["ip", "route", "show", "default"], timeout=3).strip().splitlines()
    if not out:
        return ""
    parts = out[0].split()
    return parts[parts.index("via") + 1] if "via" in parts else ""


# ─── VPS bridge ──────────────────────────────────────────────────────────────

DEFAULT_VPS_SUMMARY = (
    "echo '== uptime ==' && uptime && "
    "echo '== disk /  ==' && df -hT / | tail -1 && "
    "echo '== mem    ==' && free -h | sed -n '1,2p' && "
    "echo '== docker ==' && (docker ps --format '{{.Names}}\\t{{.Status}}' 2>/dev/null | head -10 || echo 'sin docker')"
)


def vps_status(cfg: dict, alias: str) -> tuple[str, str | None]:
    cfg_vps = (cfg.get("vps") or {}).get("hosts") or {}
    spec = cfg_vps.get(alias)
    if not isinstance(spec, dict):
        return "", f"VPS '{alias}' no configurado"
    ssh_alias = spec.get("ssh_alias") or alias
    cmd = spec.get("summary_cmd") or DEFAULT_VPS_SUMMARY
    out = run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
         ssh_alias, "bash", "-lc", cmd],
        timeout=20,
    )
    return out, None


# ─── Steam ───────────────────────────────────────────────────────────────────

def steam_library_paths() -> list[Path]:
    default_steamapps = Path.home() / ".local/share/Steam/steamapps"
    vdf = default_steamapps / "libraryfolders.vdf"
    libs: list[Path] = []
    if vdf.exists():
        try:
            text = vdf.read_text(encoding="utf-8", errors="ignore")
            for m in re.finditer(r'"path"\s*"([^"]+)"', text):
                p = Path(m.group(1)) / "steamapps"
                if p not in libs:
                    libs.append(p)
        except OSError:
            pass
    if not libs and default_steamapps.exists():
        libs.append(default_steamapps)
    return [p for p in libs if p.exists()]


def steam_games(cfg: dict) -> list[dict]:
    exclude = set((cfg.get("steam") or {}).get("exclude_appids") or [])
    games = []
    seen = set()
    for base in steam_library_paths():
        for f in base.glob("appmanifest_*.acf"):
            try:
                text = f.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            m_id = re.search(r'"appid"\s*"(\d+)"', text)
            m_name = re.search(r'"name"\s*"([^"]+)"', text)
            if not m_id or not m_name:
                continue
            appid = m_id.group(1)
            if appid in exclude or appid in seen:
                continue
            name = m_name.group(1)
            if any(s in name for s in ("Steam Linux Runtime", "Proton", "Steamworks")):
                continue
            seen.add(appid)
            games.append({"appid": appid, "name": name})
    games.sort(key=lambda g: g["name"].lower())
    return games


# ─── Acciones (Fase 3) ───────────────────────────────────────────────────────

def niri_set_output(name: str, on: bool) -> str:
    rc, stdout, stderr = _niri_run(["output", name, "on" if on else "off"])
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


def niri_dpms(on: bool) -> str:
    action = "power-on-monitors" if on else "power-off-monitors"
    rc, stdout, stderr = _niri_run(["action", action])
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


# Mapa de comandos expuestos al cliente Android (sección Comandos del tab
# Media). Cada entrada se traduce a `niri msg action <args...>`. La whitelist
# es estricta: el servidor 404ea cualquier id que no esté acá.
NIRI_CMD_MAP: dict[str, list[str]] = {
    "fullscreen-window":   ["action", "fullscreen-window"],
    "close-window":        ["action", "close-window"],
    "focus-column-right":  ["action", "focus-column-right"],
    "focus-column-left":   ["action", "focus-column-left"],
    "focus-workspace-down": ["action", "focus-workspace-down"],
    "focus-workspace-up":   ["action", "focus-workspace-up"],
    "maximize-column":     ["action", "maximize-column"],
    "toggle-overview":     ["action", "toggle-overview"],
    "media-workspace":     ["action", "spawn", "--", "media-workspace"],
    # "Fijar" un monitor: solo enfocarlo (sin acción extra). Con
    # warp-mouse-to-focus en niri, el cursor se mueve a ese monitor, así las
    # apps que se lancen después se abren en la pantalla fijada.
    "focus-monitor":       [],
}


def niri_cmd(cmd: str, output: str | None = None) -> str:
    args = NIRI_CMD_MAP.get(cmd)
    if args is None:
        return f"comando desconocido: {cmd}"
    # Si se pidió un monitor concreto, lo enfocamos antes de disparar la acción.
    # Las acciones de niri (fullscreen, columnas, workspaces…) operan sobre el
    # monitor enfocado, así que con 3 pantallas hay que mover el foco primero.
    if output:
        valid = {o["name"] for o in niri_outputs()[0]}
        if output not in valid:
            return f"monitor desconocido: {output}"
        rc, _, stderr = _niri_run(["action", "focus-monitor", output])
        if rc != 0:
            return (stderr or f"focus-monitor exit {rc}")[:300]
    if not args:
        # Comando "solo foco": el trabajo era enfocar el monitor de arriba.
        return "ok" if output else "falta output para focus-monitor"
    rc, stdout, stderr = _niri_run(args)
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


def audio_set_sink(sink: str) -> str:
    rc, _stdout, stderr = _pactl_run(["set-default-sink", sink])
    if rc != 0:
        return (stderr or f"exit {rc}")[:300]
    # Mover los streams activos al sink nuevo
    rc, stdout, _ = _pactl_run(["list", "short", "sink-inputs"])
    if rc == 0:
        for line in stdout.splitlines():
            parts = line.split("\t", 1)
            input_id = parts[0].strip() if parts else ""
            if input_id.isdigit():
                _pactl_run(["move-sink-input", input_id, sink])
    return "ok"


def mpris_action(action: str, player: str) -> str:
    base = ["-p", player]
    if action == "vol-up":
        args = [*base, "volume", "0.05+"]
    elif action == "vol-down":
        args = [*base, "volume", "0.05-"]
    elif action in ("play-pause", "next", "previous"):
        args = [*base, action]
    elif action.startswith("seek:"):
        # seek:+15  → avanza 15 s
        # seek:-15  → retrocede 15 s
        # playerctl espera "15+" / "15-" (sufijo, no prefijo).
        spec = action[len("seek:"):]
        if spec.startswith("+"):
            args = [*base, "position", f"{spec[1:]}+"]
        elif spec.startswith("-"):
            args = [*base, "position", f"{spec[1:]}-"]
        else:
            return f"seek inválido: {action}"
    else:
        return f"acción desconocida: {action}"
    rc, _, stderr = _playerctl_run(args)
    if rc == 0:
        return "ok"
    return (stderr or f"exit {rc}")[:200]


def wol_send(target: str, cfg: dict) -> str:
    mapping = (cfg.get("net") or {}).get("wake") or {}
    mac = mapping.get(target, target)
    if not re.match(r"^[0-9A-Fa-f]{2}([:.\-][0-9A-Fa-f]{2}){5}$", mac):
        return f"MAC inválida o alias no mapeado: {target}"
    out = run(["wol", mac], timeout=5)
    low = out.lower()
    if "waking up" in low or "wake-up" in low or "magic packet" in low:
        return "ok"
    return out.strip()[:200] or "ok"


def steam_launch(appid: str, cfg: dict) -> str:
    # `steam <uri>` le pasa el URI a la instancia ya corriendo. Si el
    # usuario tiene configurado gamescope a nivel de juego en Steam,
    # se aplica automaticamente. Pasar steam:// como primer arg a
    # gamescope-auto NO funciona — gamescope-auto wrappea binarios.
    cmd = ["steam", f"steam://rungameid/{appid}"]
    uid = os.getuid()
    env = {**os.environ, "XDG_RUNTIME_DIR": f"/run/user/{uid}", "LC_ALL": "C"}
    full = ["systemd-run", "--user", "--collect", "--quiet", "--", *cmd]
    try:
        proc = subprocess.run(
            full, capture_output=True, text=True, timeout=10, env=env,
        )
    except subprocess.TimeoutExpired:
        return "timeout esperando a systemd-run"
    except FileNotFoundError:
        return "systemd-run no encontrado"
    if proc.returncode == 0:
        return "ok"
    return ((proc.stderr or proc.stdout or "").strip()
            or f"exit {proc.returncode}")


# Power actions ──────────────────────────────────────────────────────────────

POWER_CMDS = {
    "off":     ["systemctl", "poweroff"],
    "reboot":  ["systemctl", "reboot"],
    "suspend": ["systemctl", "suspend"],
    "lock":    ["loginctl", "lock-sessions"],
}


def execute_power(action: str) -> str:
    cmd = POWER_CMDS.get(action)
    if cmd is None:
        return f"acción desconocida: {action}"
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=10,
        )
    except subprocess.TimeoutExpired:
        return "timeout"
    except FileNotFoundError:
        return f"{cmd[0]} no encontrado"
    if proc.returncode == 0:
        return "ok"
    return ((proc.stderr or proc.stdout or "").strip()
            or f"exit {proc.returncode}")


def execute_kill(pid: str) -> str:
    if not pid.isdigit():
        return f"pid inválido: {pid}"
    try:
        proc = subprocess.run(
            ["kill", "-TERM", pid],
            capture_output=True, text=True, timeout=5,
        )
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode == 0:
        return "ok"
    return ((proc.stderr or proc.stdout or "").strip()
            or f"exit {proc.returncode}")


def execute_svc(svc: str, action: str, cfg: dict) -> str:
    if action not in ("start", "stop", "restart"):
        return f"acción desconocida: {action}"
    allowed = set(cfg.get("services", {}).get("manageable", []))
    unit_simple = svc.split(".", 1)[0]
    if svc not in allowed and unit_simple not in allowed:
        return f"unit '{svc}' no está en services.manageable"
    unit = svc if "." in svc else f"{svc}.service"
    try:
        proc = subprocess.run(
            ["systemctl", action, unit],
            capture_output=True, text=True, timeout=15,
        )
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode == 0:
        return "ok"
    return ((proc.stderr or proc.stdout or "").strip()
            or f"exit {proc.returncode}")


def execute_app(name: str, cfg: dict) -> str:
    apps = cfg.get("apps") or {}
    spec = apps.get(name)
    if not isinstance(spec, dict):
        return f"app '{name}' no configurada"
    cmd = spec.get("cmd")
    if not isinstance(cmd, list) or not cmd:
        return f"app '{name}' sin cmd válido"
    uid = os.getuid()
    env = {**os.environ, "XDG_RUNTIME_DIR": f"/run/user/{uid}", "LC_ALL": "C"}
    full = ["systemd-run", "--user", "--collect", "--quiet", "--", *cmd]
    try:
        proc = subprocess.run(
            full, capture_output=True, text=True, timeout=10, env=env,
        )
    except subprocess.TimeoutExpired:
        return "timeout esperando a systemd-run"
    except FileNotFoundError:
        return "systemd-run no encontrado"
    if proc.returncode == 0:
        return "ok"
    return ((proc.stderr or proc.stdout or "").strip()
            or f"exit {proc.returncode}")


def execute_apply_updates() -> str:
    """Arranca el oneshot pacman-update.service (instalado por bot-comandos-torre
    o por nosotros). El daemon NO espera a que termine — devuelve inmediatamente
    'started' o el error. El cliente puede pollear /api/v1/updates después."""
    try:
        proc = subprocess.run(
            ["systemctl", "start", "pacman-update.service"],
            capture_output=True, text=True, timeout=10,
        )
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode == 0:
        return "started"
    return ((proc.stderr or proc.stdout or "").strip()
            or f"exit {proc.returncode}")


# ─── Context ─────────────────────────────────────────────────────────────────

class Context:
    def __init__(self, cfg, metrics, history, audit, broker,
                 alerts=None, sudo=None):
        self.cfg = cfg
        self.metrics = metrics
        self.history = history
        self.audit = audit
        self.broker = broker
        self.alerts = alerts
        self.sudo = sudo


_ALERT_TITLES = {
    "cpu_pct":        ("⚠️ CPU alta",        "{v:.0f} %"),
    "ram_pct":        ("⚠️ RAM alta",        "{v:.0f} %"),
    "disk_pct":       ("⚠️ Disco lleno",     "{v:.0f} %"),
    "cpu_temp_c":     ("🔥 CPU caliente",    "{v:.0f} °C"),
    "gpu_temp_c":     ("🔥 GPU caliente",    "{v:.0f} °C"),
    "gpu_pct":        ("⚠️ GPU saturada",    "{v:.0f} %"),
    "load1_per_core": ("⚠️ Load alta",       "{v:.2f}/core"),
}


def _alert_label(key: str, value: float, disk_mount: str = "") -> tuple[str, str]:
    title, vfmt = _ALERT_TITLES.get(key, (key, "{v}"))
    val_str = vfmt.format(v=value)
    if key == "disk_pct" and disk_mount:
        val_str += f" ({disk_mount})"
    return title, val_str


# ─── Monitor loop ────────────────────────────────────────────────────────────

def monitor_loop(ctx: Context) -> None:
    cfg = ctx.cfg
    interval = int(cfg["monitor"]["interval_s"])
    state = {
        "failed_services": set(),
        "failed_services_seen": False,
        "sessions": set(),
        "sessions_seen": False,
        "last_tick": time.monotonic(),
    }

    ctx.metrics.cpu_pct()
    ctx.metrics.net_throughput()
    time.sleep(2)

    last_prune = 0.0
    while True:
        t0 = time.monotonic()
        wall_skip = t0 - state["last_tick"] - interval
        if wall_skip > 60 and state["last_tick"] != 0:
            ctx.broker.publish("resume", {"gap_s": wall_skip})
            ctx.audit.log("resume", gap_s=wall_skip)
        state["last_tick"] = t0

        try:
            cpu = ctx.metrics.cpu_pct()
            mem = ctx.metrics.memory()
            disk_mount, disk_pct = ctx.metrics.disk_max_pct()
            cpu_temp = ctx.metrics.cpu_temp_max()
            gpu_temp = ctx.metrics.gpu_max_temp()
            gpu_pct = ctx.metrics.gpu_max_pct()
            la = ctx.metrics.loadavg()

            cores = ctx.metrics.cpu_count()
            load_per_core = la[0] / cores if cores else 0.0

            snap = {
                "cpu_pct": cpu, "ram_pct": mem["pct"],
                "gpu_pct": gpu_pct, "cpu_temp": cpu_temp,
                "gpu_temp": gpu_temp, "load1": la[0],
                "disk_pct": disk_pct, "disk_mount": disk_mount,
            }
            ctx.history.insert(int(time.time()), snap)
            ctx.broker.publish("metric_tick", snap)

            # Evaluar alertas con histéresis
            if ctx.alerts is not None:
                for key, val in (
                    ("cpu_pct", cpu), ("ram_pct", mem["pct"]),
                    ("disk_pct", disk_pct), ("cpu_temp_c", cpu_temp),
                    ("gpu_temp_c", gpu_temp), ("gpu_pct", gpu_pct),
                    ("load1_per_core", load_per_core),
                ):
                    if ctx.alerts.evaluate(key, val):
                        title, val_str = _alert_label(key, val, disk_mount)
                        ctx.broker.publish("alert", {
                            "key": key, "value": val,
                            "title": title, "value_str": val_str,
                            "snap": snap,
                        })
                        ctx.audit.log("alert", key=key, value=val)

            failed_now = set(list_failed_services())
            new_failed = failed_now - state["failed_services"]
            if new_failed and state["failed_services_seen"]:
                for svc in sorted(new_failed):
                    ctx.broker.publish("service_failed", {"unit": svc})
                    ctx.audit.log("service_failed", service=svc)
            state["failed_services"] = failed_now
            state["failed_services_seen"] = True

            sessions_now = list_sessions()
            new_sess = sessions_now - state["sessions"]
            if new_sess and state["sessions_seen"]:
                for s in sorted(new_sess):
                    ctx.broker.publish("session_new", {"session": s})
                    ctx.audit.log("session_new", session=s)
            state["sessions"] = sessions_now
            state["sessions_seen"] = True

            now = time.time()
            if now - last_prune > 6 * 3600:
                ctx.history.prune()
                last_prune = now
        except Exception as e:
            print(f"monitor: tick failed: {e!r}", file=sys.stderr)

        elapsed = time.monotonic() - t0
        time.sleep(max(1.0, interval - elapsed))


# ─── Tick rápido (push de métricas vivas a SSE) ──────────────────────────────

def fast_tick_loop(ctx: Context) -> None:
    """Publica un metric_tick más liviano cada `monitor.tick_publish_s` para
    feed de la app en vivo, sin tocar el SQLite. CPU% se mide entre lecturas.
    """
    interval = max(1, int(ctx.cfg["monitor"].get("tick_publish_s", 5)))
    ctx.metrics.cpu_pct()
    time.sleep(1)
    while True:
        try:
            cpu = ctx.metrics.cpu_pct()
            mem = ctx.metrics.memory()
            la = ctx.metrics.loadavg()
            gpu_pct = ctx.metrics.gpu_max_pct()
            gpu_temp = ctx.metrics.gpu_max_temp()
            cpu_temp = ctx.metrics.cpu_temp_max()
            ctx.broker.publish("metric_tick", {
                "cpu_pct": cpu, "ram_pct": mem["pct"],
                "gpu_pct": gpu_pct, "cpu_temp": cpu_temp,
                "gpu_temp": gpu_temp, "load1": la[0],
                "live": True,
            })
        except Exception as e:
            print(f"fast_tick: failed: {e!r}", file=sys.stderr)
        time.sleep(interval)


# ─── Boot notification ───────────────────────────────────────────────────────

def maybe_publish_boot(cfg: dict, audit: Audit, broker) -> None:
    try:
        with open("/proc/sys/kernel/random/boot_id") as f:
            boot_id = f.read().strip()
    except OSError:
        return
    state_dir = Path(cfg["history"]["db_path"]).parent
    boot_marker = state_dir / "last_boot_id"
    last_boot_id = ""
    if boot_marker.exists():
        try:
            last_boot_id = boot_marker.read_text().strip()
        except OSError:
            pass
    if not boot_id or boot_id == last_boot_id:
        return
    try:
        with open("/proc/uptime") as f:
            up = float(f.read().split()[0])
    except OSError:
        up = 0.0
    hostname = run(["hostname"]).strip() or "?"
    broker.publish("boot", {
        "boot_id": boot_id, "uptime_s": up, "hostname": hostname,
    })
    audit.log("boot", uptime_s=up, boot_id=boot_id)
    try:
        boot_marker.write_text(boot_id + "\n")
    except OSError as e:
        print(f"could not persist boot marker: {e}", file=sys.stderr)


# ─── main ────────────────────────────────────────────────────────────────────

def main() -> None:
    cfg = load_config(CONFIG_PATH)
    metrics = Metrics()
    history = History(cfg["history"]["db_path"], cfg["history"]["retain_days"])
    audit = Audit(cfg["audit"]["log_path"])

    from http_server import EventBroker, start_http_server
    from sudo_broker import SudoBroker
    import input_control
    broker = EventBroker()
    alerts = Alerts(cfg)
    sudo = SudoBroker()

    ctx = Context(cfg, metrics, history, audit, broker,
                  alerts=alerts, sudo=sudo)

    audit.log("start", pid=os.getpid(), config=CONFIG_PATH)
    print(
        f"apppanda-backend starting (monitor={cfg['monitor']['enabled']}, "
        f"http={cfg['http']['enabled']})",
        file=sys.stderr,
    )

    if cfg["http"]["enabled"]:
        api = SimpleNamespace(
            ctx=ctx,
            run=run,
            top_processes=top_processes,
            list_failed_services=list_failed_services,
            list_sessions=list_sessions,
            niri_outputs=niri_outputs,
            niri_set_output=niri_set_output,
            niri_dpms=niri_dpms,
            niri_cmd=niri_cmd,
            audio_sinks=audio_sinks,
            audio_default_sink=audio_default_sink,
            audio_set_sink=audio_set_sink,
            mpris_players=mpris_players,
            mpris_status=mpris_status,
            mpris_action=mpris_action,
            player_video_fullscreen=player_video_fullscreen,
            lan_neighbors=lan_neighbors,
            default_gateway=default_gateway,
            vps_status=vps_status,
            steam_games=steam_games,
            steam_launch=steam_launch,
            wol_send=wol_send,
            execute_power=execute_power,
            execute_kill=execute_kill,
            execute_svc=execute_svc,
            execute_app=execute_app,
            execute_apply_updates=execute_apply_updates,
            input_mouse_move=input_control.mouse_move,
            input_mouse_click=input_control.mouse_click,
            input_mouse_scroll=input_control.mouse_scroll,
            input_key_press=input_control.key_press,
            input_type_text=input_control.type_text,
        )
        start_http_server(
            api, broker,
            host=cfg["http"]["host"],
            port=int(cfg["http"]["port"]),
            tokens=list(cfg["http"]["tokens"]),
            audit=audit,
            tailscale_auth=cfg["auth"]["tailscale"],
        )

    maybe_publish_boot(cfg, audit, broker)

    if cfg["monitor"]["enabled"]:
        threading.Thread(
            target=monitor_loop, args=(ctx,), daemon=True, name="monitor",
        ).start()
        threading.Thread(
            target=fast_tick_loop, args=(ctx,), daemon=True, name="fast-tick",
        ).start()

    # Loop main thread (idle, daemon threads hacen el trabajo)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
