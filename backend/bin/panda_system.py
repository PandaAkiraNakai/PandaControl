"""Red, VPS, Steam, mini terminal, Docker y demás acciones de sistema; íconos de apps.

Separado de apppanda-backend.py; ver ese archivo para el diseño general.
"""

import json
import os
import pwd
import re
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path

from panda_core import run
from panda_metrics import list_sessions


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


# ─── Mini terminal (opt-in) ───────────────────────────────────────────────────

def terminal_run(cfg: dict, cmd: str) -> dict:
    """Ejecuta `bash -lc <cmd>` como el usuario del daemon. Gated por
    [terminal].enabled (off por defecto). Sin sudo. Timeout y salida capada."""
    tcfg = cfg.get("terminal") or {}
    if not tcfg.get("enabled"):
        return {"error": "terminal deshabilitada ([terminal].enabled=true)",
                "exit": -1}
    cmd = (cmd or "").strip()
    if not cmd:
        return {"error": "comando vacío", "exit": -1}
    timeout = int(tcfg.get("timeout_s", 20))
    max_bytes = int(tcfg.get("max_output_kb", 64)) * 1024
    uid = os.getuid()
    env = {**os.environ, "XDG_RUNTIME_DIR": f"/run/user/{uid}", "LC_ALL": "C.UTF-8"}
    try:
        proc = subprocess.run(
            ["bash", "-lc", cmd], capture_output=True, text=True,
            timeout=timeout, env=env,
        )
    except subprocess.TimeoutExpired:
        return {"error": f"timeout ({timeout}s)", "exit": -1}
    out = proc.stdout or ""
    err = proc.stderr or ""
    truncated = len(out) > max_bytes or len(err) > max_bytes
    return {
        "stdout": out[:max_bytes],
        "stderr": err[:max_bytes],
        "exit": proc.returncode,
        "truncated": truncated,
    }


# Memorias (visor de solo lectura) ─────────────────────────────────────────────

def memorias_list(cfg: dict, include_inactive: bool = False) -> dict:
    """Lee la tabla `memorias` de la base SQLite del CLI `mem` y la devuelve
    ordenada por última actualización. SOLO lectura (modo ro) y SIN tocar
    Ollama ni embeddings: es un visor, no el buscador semántico.

    Orden: las activas primero, luego por `actualizado` descendente."""
    mcfg = cfg.get("memorias") or {}
    if not mcfg.get("enabled", True):
        return {"enabled": False, "total": 0, "memorias": []}
    db = os.path.expanduser(mcfg.get("db_path", "~/.local/share/mem/memory.db"))
    if not os.path.exists(db):
        return {"enabled": True, "error": f"base no encontrada: {db}",
                "total": 0, "memorias": []}
    where = "" if include_inactive else "WHERE activo = 1"
    try:
        # Conexión read-only por URI: nunca escribe ni crea la base.
        conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
        try:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                f"""SELECT id, tipo, nombre, resumen, contenido, tags,
                           activo, creado, actualizado, dispositivo
                    FROM memorias {where}
                    ORDER BY activo DESC, actualizado DESC""",
            ).fetchall()
        finally:
            conn.close()
    except sqlite3.Error as e:
        return {"enabled": True, "error": f"sqlite: {e}",
                "total": 0, "memorias": []}
    mems = [
        {
            "id": r["id"],
            "tipo": r["tipo"],
            "nombre": r["nombre"],
            "resumen": r["resumen"],
            "contenido": r["contenido"],
            "tags": r["tags"] or "",
            "activo": r["activo"],
            "creado": r["creado"],
            "actualizado": r["actualizado"],
            "dispositivo": r["dispositivo"],
        }
        for r in rows
    ]
    return {"enabled": True, "total": len(mems), "memorias": mems}


def pedidos_list(cfg: dict, include_done: bool = False) -> dict:
    """Lee la tabla `pedidos` de la misma base SQLite del CLI `mem`. Solo
    lectura. Orden: en_progreso primero, luego pendiente; dentro de cada uno
    por prioridad (urgente→baja) y por fecha de vencimiento ascendente.

    Por defecto oculta los `hecho`/`cancelado`; `include_done` los trae."""
    mcfg = cfg.get("memorias") or {}
    db = os.path.expanduser(mcfg.get("db_path", "~/.local/share/mem/memory.db"))
    if not os.path.exists(db):
        return {"error": f"base no encontrada: {db}", "total": 0, "pedidos": []}
    where = "" if include_done else "WHERE estado IN ('pendiente', 'en_progreso')"
    try:
        conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
        try:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                f"""SELECT id, titulo, detalle, estado, prioridad, vence,
                           notas, creado, actualizado, completado
                    FROM pedidos {where}
                    ORDER BY
                        CASE estado WHEN 'en_progreso' THEN 0 WHEN 'pendiente' THEN 1
                                    WHEN 'hecho' THEN 2 ELSE 3 END,
                        CASE prioridad WHEN 'urgente' THEN 0 WHEN 'alta' THEN 1
                                       WHEN 'normal' THEN 2 ELSE 3 END,
                        (vence IS NULL), vence ASC""",
            ).fetchall()
        finally:
            conn.close()
    except sqlite3.Error as e:
        return {"error": f"sqlite: {e}", "total": 0, "pedidos": []}
    pedidos = [
        {
            "id": r["id"],
            "titulo": r["titulo"],
            "detalle": r["detalle"] or "",
            "estado": r["estado"],
            "prioridad": r["prioridad"],
            "vence": r["vence"] or "",
            "notas": r["notas"] or "",
            "creado": r["creado"],
            "actualizado": r["actualizado"],
            "completado": r["completado"] or "",
        }
        for r in rows
    ]
    return {"total": len(pedidos), "pedidos": pedidos}


# Docker (lista y gestión de contenedores) ─────────────────────────────────────

_DOCKER_NAME_RE = re.compile(r"[a-zA-Z0-9_.-]{1,128}")


def docker_list(cfg: dict) -> dict:
    """Lista todos los contenedores (incluidos los parados) vía `docker ps -a`.
    Orden: corriendo primero, luego por nombre."""
    if not (cfg.get("docker") or {}).get("enabled", True):
        return {"enabled": False, "total": 0, "containers": []}
    out = run(["docker", "ps", "-a", "--format", "{{json .}}"], timeout=10)
    if out.startswith("<"):  # <timeout> / <no encontrado: docker> / <exit N>
        return {"enabled": True, "error": out, "total": 0, "containers": []}
    containers = []
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            j = json.loads(line)
        except json.JSONDecodeError:
            continue
        containers.append({
            "id": (j.get("ID") or "")[:12],
            "name": j.get("Names", ""),
            "image": j.get("Image", ""),
            "state": j.get("State", ""),     # running / exited / created / paused
            "status": j.get("Status", ""),   # texto humano: "Up 3 hours"
            "ports": j.get("Ports", ""),
        })
    containers.sort(key=lambda c: (c["state"] != "running", c["name"].lower()))
    return {"enabled": True, "total": len(containers), "containers": containers}


def docker_logs(cfg: dict, name: str, n: int = 200) -> dict:
    """Últimas `n` líneas del log de un contenedor (stdout+stderr combinados)."""
    if not (cfg.get("docker") or {}).get("enabled", True):
        return {"error": "docker deshabilitado", "lines": []}
    if not _DOCKER_NAME_RE.fullmatch(name or ""):
        return {"error": "nombre de contenedor inválido", "lines": []}
    n = max(1, min(int(n), 1000))
    out = run(["docker", "logs", "--tail", str(n), name], timeout=15)
    lines = out.splitlines()[-n:]
    return {"name": name, "lines": lines}


def docker_action(cfg: dict, name: str, action: str) -> str:
    """start / stop / restart de un contenedor. Devuelve el output de docker."""
    if not (cfg.get("docker") or {}).get("enabled", True):
        return "docker deshabilitado"
    if action not in ("start", "stop", "restart"):
        return f"acción inválida: {action}"
    if not _DOCKER_NAME_RE.fullmatch(name or ""):
        return "nombre de contenedor inválido"
    # docker imprime el nombre/ID del contenedor al tener éxito; un fallo
    # empieza con "Error..." o con un marcador "<...>" de run(). Normalizamos
    # a "ok" para que la app lo marque como acción exitosa.
    out = run(["docker", action, name], timeout=30).strip()
    if out.startswith("<") or out.lower().startswith("error"):
        return out or "error"
    return "ok"


# Power actions ──────────────────────────────────────────────────────────────

POWER_CMDS = {
    "off":     ["systemctl", "poweroff"],
    "reboot":  ["systemctl", "reboot"],
    "suspend": ["systemctl", "suspend"],
    "lock":    ["loginctl", "lock-sessions"],
}


def execute_power(action: str) -> str:
    if action == "unlock":
        return execute_unlock()
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


# Desbloqueo ──────────────────────────────────────────────────────────────────

UNLOCK_UNIT = "apppanda-desbloqueo.service"

# Códigos de salida de /usr/local/bin/apppanda-desbloqueo (ver su docstring).
UNLOCK_EXIT_MESSAGES = {
    10: "ya había una sesión gráfica activa",
    11: "no se encontró la pantalla de inicio de sesión de plasmalogin",
    12: "plasmalogin rechazó la contraseña (revisa [sudo_app].password_file)",
    13: "plasmalogin no respondió",
    14: "falta la contraseña en [sudo_app].password_file",
    15: "configuración [desbloqueo] inválida",
}


def graphical_session() -> dict | None:
    """Sesión gráfica (wayland/x11) del usuario del daemon, prefiriendo la
    activa. None = no hay ninguna: el PC está en la pantalla de login."""
    user = pwd.getpwuid(os.getuid()).pw_name
    found = None
    for sid in sorted(list_sessions()):
        out = run(["loginctl", "show-session", sid, "-p", "Name", "-p", "Type",
                   "-p", "Class", "-p", "State", "-p", "Active", "-p", "LockedHint"])
        props = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
        if (props.get("Name") != user or props.get("Class") != "user"
                or props.get("Type") not in ("wayland", "x11")
                or props.get("State") == "closing"):
            continue
        session = {
            "id": sid,
            "type": props["Type"],
            "active": props.get("Active") == "yes",
            "locked": props.get("LockedHint") == "yes",
        }
        if session["active"]:
            return session
        found = found or session
    return found


def session_state() -> dict:
    """login = pantalla de inicio de sesión (sin sesión gráfica activa),
    locked = sesión con la pantalla bloqueada, unlocked = en uso."""
    s = graphical_session()
    if s is None or not s["active"]:
        return {"state": "login", "session": s["id"] if s else None, "type": None}
    return {"state": "locked" if s["locked"] else "unlocked",
            "session": s["id"], "type": s["type"]}


def execute_unlock() -> str:
    s = graphical_session()
    if s is not None and s["active"]:
        if not s["locked"]:
            return "ok"
        out = run(["loginctl", "unlock-session", s["id"]]).strip()
        if out:
            return out
        # KScreenLocker baja LockedHint al cerrar su greeter; tarda <1 s.
        for _ in range(20):
            time.sleep(0.25)
            current = graphical_session()
            if current is None or not current["locked"]:
                return "ok"
        return "la sesión sigue bloqueada"

    # Pantalla de login: el oneshot (root) le pasa la contraseña a plasmalogin.
    try:
        proc = subprocess.run(
            ["systemctl", "start", UNLOCK_UNIT],
            capture_output=True, text=True, timeout=40,
        )
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode != 0:
        status = run(["systemctl", "show", "-p", "ExecMainStatus", "--value",
                      UNLOCK_UNIT]).strip()
        if status.isdigit() and int(status) in UNLOCK_EXIT_MESSAGES:
            return UNLOCK_EXIT_MESSAGES[int(status)]
        return ((proc.stderr or proc.stdout or "").strip()
                or f"exit {proc.returncode}")
    # logind registra la sesión apenas PAM la abre, antes de startplasma.
    for _ in range(40):
        current = graphical_session()
        if current is not None and current["active"]:
            return "ok"
        time.sleep(0.5)
    return "login aceptado, pero la sesión gráfica todavía no aparece"


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


# ─── Íconos de apps (para la grilla de Apps) ──────────────────────────────────
# El ícono sale de `icon` en [apps.X] (nombre de ícono o ruta absoluta) o del
# Icon= del .desktop que aparezca en el cmd (p. ej. `uwsm app -- steam.desktop`).
# Se busca en el tema de íconos de GTK del usuario y se entrega como PNG de
# 128 px (los SVG se rasterizan con rsvg-convert).

_ICON_PX = 128
_ICON_CACHE: dict[str, bytes | None] = {}
_ICON_LOCK = threading.Lock()


def _desktop_icon(desktop_id: str) -> str | None:
    home = Path.home()
    for base in (home / ".local/share/applications", Path("/usr/share/applications")):
        path = base / desktop_id
        try:
            for line in path.read_text(errors="replace").splitlines():
                if line.startswith("Icon="):
                    return line[5:].strip() or None
        except OSError:
            continue
    return None


def _gtk_icon_theme() -> str:
    try:
        for line in (Path.home() / ".config/gtk-3.0/settings.ini").read_text().splitlines():
            key, _, val = line.partition("=")
            if key.strip() == "gtk-icon-theme-name" and val.strip():
                return val.strip()
    except OSError:
        pass
    return "hicolor"


def _icon_size_rank(path: Path) -> int:
    """Menor = mejor: scalable primero, después el tamaño más cercano a 128."""
    for part in path.parts:
        if part == "scalable":
            return 0
        m = re.fullmatch(r"(\d+)x\d+(?:@\d+)?", part)
        if m:
            return 1 + abs(int(m.group(1)) - _ICON_PX)
    return 999


def _find_icon_file(name: str) -> Path | None:
    if name.startswith("/"):
        p = Path(name)
        return p if p.is_file() else None
    themes = [_gtk_icon_theme()]
    if themes[0].endswith(("-Dark", "-Light")):
        themes.append(themes[0].rsplit("-", 1)[0])  # Papirus-Dark -> Papirus
    themes += ["breeze-dark", "breeze", "Adwaita", "hicolor"]
    roots = [Path.home() / ".local/share/icons", Path("/usr/share/icons")]
    for theme in dict.fromkeys(themes):
        dirs = [root / theme for root in roots if (root / theme).is_dir()]
        # Layout tamaño/apps (hicolor, Papirus) y apps/tamaño (breeze).
        found = [p for d in dirs for pat in ("*/apps", "apps/*")
                 for ext in ("svg", "png") for p in d.glob(f"{pat}/{name}.{ext}")]
        if found:
            return min(found, key=_icon_size_rank)
    for ext in ("svg", "png"):
        p = Path("/usr/share/pixmaps") / f"{name}.{ext}"
        if p.is_file():
            return p
    return None


def _app_icon_name(spec: dict) -> str | None:
    icon = spec.get("icon")
    if isinstance(icon, str) and icon.strip():
        return icon.strip()
    for arg in spec.get("cmd") or []:
        if isinstance(arg, str) and arg.endswith(".desktop"):
            return _desktop_icon(Path(arg).name)
    return None


def app_icon_png(name: str, cfg: dict) -> bytes | None:
    spec = (cfg.get("apps") or {}).get(name)
    if not isinstance(spec, dict):
        return None
    with _ICON_LOCK:
        if name in _ICON_CACHE:
            return _ICON_CACHE[name]
    data = None
    icon = _app_icon_name(spec)
    path = _find_icon_file(icon) if icon else None
    if path is not None:
        try:
            if path.suffix == ".svg":
                proc = subprocess.run(
                    ["rsvg-convert", "-w", str(_ICON_PX), "-h", str(_ICON_PX),
                     "--keep-aspect-ratio", str(path)],
                    capture_output=True, timeout=10,
                )
                data = proc.stdout if proc.returncode == 0 and proc.stdout else None
            elif path.stat().st_size <= 2 * 1024 * 1024:
                data = path.read_bytes()
        except (OSError, subprocess.TimeoutExpired):
            data = None
    with _ICON_LOCK:
        _ICON_CACHE[name] = data
    return data


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
