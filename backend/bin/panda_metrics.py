"""Métricas del sistema, alertas con histéresis, histórico SQLite y consultas de sistema.

Separado de apppanda-backend.py; ver ese archivo para el diseño general.
"""

import json
import os
import re
import shutil
import sqlite3
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

from panda_core import DEFAULT_ALERTS, now_iso, run


# ─── Metrics (snapshots de sistema) ──────────────────────────────────────────

# Claves de temperatura en la salida de `sensors -j` (temp1_input, temp2_input…).
_TEMP_INPUT_RE = re.compile(r"temp\d+_input")


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
                            # Solo temperaturas: `sensors -j` también trae
                            # ventiladores (fanN_input, en RPM), voltajes,
                            # potencia y frecuencias. Antes se tomaba el
                            # primer *_input y el `cpu_fan` de ASUS entraba
                            # como "CPU" a ~1900 °C y disparaba la alerta.
                            if _TEMP_INPUT_RE.fullmatch(k) and isinstance(v, (int, float)):
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


class Calendar:
    """Calendario self-hosted: eventos en una SQLite propia del daemon, con
    CRUD completo. Las fechas se guardan como texto ISO local: `YYYY-MM-DD`
    para eventos de todo el día, `YYYY-MM-DDTHH:MM` para los que tienen hora.
    """

    _COLS = ("id", "titulo", "descripcion", "ubicacion", "inicio", "fin",
             "todo_el_dia", "color", "recordatorio_min", "recordado",
             "creado", "actualizado")

    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("""
            CREATE TABLE IF NOT EXISTS eventos (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                titulo           TEXT NOT NULL,
                descripcion      TEXT,
                ubicacion        TEXT,
                inicio           TEXT NOT NULL,
                fin              TEXT,
                todo_el_dia      INTEGER NOT NULL DEFAULT 0,
                color            TEXT,
                recordatorio_min INTEGER,
                recordado        INTEGER NOT NULL DEFAULT 0,
                creado           TEXT NOT NULL,
                actualizado      TEXT NOT NULL
            )
        """)
        self._conn.commit()

    @staticmethod
    def _row(r: sqlite3.Row) -> dict:
        return {
            "id": r["id"],
            "titulo": r["titulo"],
            "descripcion": r["descripcion"] or "",
            "ubicacion": r["ubicacion"] or "",
            "inicio": r["inicio"],
            "fin": r["fin"] or "",
            "todo_el_dia": bool(r["todo_el_dia"]),
            "color": r["color"] or "",
            "recordatorio_min": r["recordatorio_min"],
            "creado": r["creado"],
            "actualizado": r["actualizado"],
        }

    def list(self, d_from: str, d_to: str) -> list[dict]:
        """Eventos que solapan el rango de fechas [d_from, d_to] (YYYY-MM-DD)."""
        with self._lock:
            rows = self._conn.execute(
                """SELECT * FROM eventos
                   WHERE substr(inicio, 1, 10) <= ?
                     AND substr(COALESCE(NULLIF(fin, ''), inicio), 1, 10) >= ?
                   ORDER BY inicio""",
                (d_to, d_from),
            ).fetchall()
        return [self._row(r) for r in rows]

    def get(self, eid: int) -> dict | None:
        with self._lock:
            r = self._conn.execute(
                "SELECT * FROM eventos WHERE id = ?", (eid,)).fetchone()
        return self._row(r) if r else None

    def create(self, e: dict) -> dict:
        now = now_iso()
        with self._lock:
            cur = self._conn.execute(
                """INSERT INTO eventos
                   (titulo, descripcion, ubicacion, inicio, fin, todo_el_dia,
                    color, recordatorio_min, recordado, creado, actualizado)
                   VALUES (?,?,?,?,?,?,?,?,0,?,?)""",
                (e["titulo"], e.get("descripcion", ""), e.get("ubicacion", ""),
                 e["inicio"], e.get("fin") or None,
                 1 if e.get("todo_el_dia") else 0, e.get("color", ""),
                 e.get("recordatorio_min"), now, now),
            )
            self._conn.commit()
            eid = cur.lastrowid
        return self.get(eid)

    def update(self, eid: int, e: dict) -> dict | None:
        if self.get(eid) is None:
            return None
        # Cambiar inicio/hora/recordatorio resetea el flag de recordado.
        with self._lock:
            self._conn.execute(
                """UPDATE eventos SET
                     titulo=?, descripcion=?, ubicacion=?, inicio=?, fin=?,
                     todo_el_dia=?, color=?, recordatorio_min=?, recordado=0,
                     actualizado=?
                   WHERE id=?""",
                (e["titulo"], e.get("descripcion", ""), e.get("ubicacion", ""),
                 e["inicio"], e.get("fin") or None,
                 1 if e.get("todo_el_dia") else 0, e.get("color", ""),
                 e.get("recordatorio_min"), now_iso(), eid),
            )
            self._conn.commit()
        return self.get(eid)

    def delete(self, eid: int) -> bool:
        with self._lock:
            cur = self._conn.execute("DELETE FROM eventos WHERE id = ?", (eid,))
            self._conn.commit()
            return cur.rowcount > 0

    def pop_due_reminders(self, now: datetime) -> list[dict]:
        """Devuelve los eventos cuyo recordatorio ya toca y los marca como
        recordados. Los que ya se pasaron de largo (el daemon estuvo apagado)
        se marcan en silencio para no re-escanearlos."""
        fire: list[dict] = []
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM eventos "
                "WHERE recordado = 0 AND recordatorio_min IS NOT NULL",
            ).fetchall()
            mark: list[int] = []
            for r in rows:
                inicio = _parse_dt(r["inicio"], bool(r["todo_el_dia"]))
                if inicio is None:
                    mark.append(r["id"])
                    continue
                remind_at = inicio - timedelta(minutes=int(r["recordatorio_min"]))
                if remind_at <= now:
                    mark.append(r["id"])
                    # Solo notificar si no se pasó hace más de 2 min del inicio.
                    if inicio >= now - timedelta(minutes=2):
                        fire.append(self._row(r))
            if mark:
                self._conn.executemany(
                    "UPDATE eventos SET recordado = 1 WHERE id = ?",
                    [(i,) for i in mark],
                )
                self._conn.commit()
        return fire


def _parse_dt(s: str, all_day: bool) -> datetime | None:
    """Parsea `YYYY-MM-DD` o `YYYY-MM-DDTHH:MM` a datetime local naïve."""
    try:
        if all_day or "T" not in s:
            return datetime.strptime(s[:10], "%Y-%m-%d")
        return datetime.strptime(s[:16], "%Y-%m-%dT%H:%M")
    except (ValueError, TypeError):
        return None


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
