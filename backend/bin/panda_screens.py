"""Compositores (niri, KDE Plasma, COSMIC, Hyprland): pantallas, DPMS, comandos de ventana y escenas.

Separado de apppanda-backend.py; ver ese archivo para el diseño general.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from panda_core import run
from panda_media import _pactl_run, _playerctl_env, _playerctl_run
from panda_system import steam_games


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


def _process_running(comm: str, skip_arg: str | None = None) -> bool:
    """True si hay un proceso vivo cuyo /proc/<pid>/comm sea exactamente `comm`.
    Con `skip_arg` se ignoran los que lleven ese argumento (p. ej. un
    `kwin_wayland --virtual` de pruebas no es la sesión del usuario)."""
    try:
        pids = [p for p in os.listdir("/proc") if p.isdigit()]
    except OSError:
        return False
    for pid in pids:
        try:
            with open(f"/proc/{pid}/comm") as f:
                if f.read().strip() != comm:
                    continue
            if skip_arg:
                with open(f"/proc/{pid}/cmdline", "rb") as f:
                    if skip_arg.encode() in f.read().split(b"\0"):
                        continue
            return True
        except OSError:
            continue
    return False


def active_compositor() -> str:
    """'niri', 'kde', 'cosmic', 'hyprland' o 'unknown'. Se resuelve en cada
    llamada (no se cachea) porque el usuario alterna de sesión (niri <-> Plasma
    <-> COSMIC <-> Hyprland) sin reiniciar el backend, y este puede quedar
    corriendo de una sesión a otra."""
    if "NIRI_SOCKET" in niri_socket_env():
        return "niri"
    if _process_running("Hyprland"):
        return "hyprland"
    if _process_running("kwin_wayland", skip_arg="--virtual") or _process_running("kwin_x11"):
        return "kde"
    if _process_running("cosmic-comp"):
        return "cosmic"
    return "unknown"


def _niri_outputs() -> tuple[list[dict], str | None]:
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
    """Envía una tecla al compositor vía wtype (zwp_virtual_keyboard_v1). KWin
    no ofrece ese protocolo: ahí la tecla sale por ydotoold (input_control)."""
    rc, stdout, stderr = _wayland_client_run("wtype", [key], 5)
    if rc != 0:
        err = (stderr or stdout or f"exit {rc}").strip()
        import input_control  # perezoso, igual que en _cosmic_focus_output
        if input_control._UNSUPPORTED_MSG in err:
            return input_control._uinput_key(key)
        return err[:200]
    return "ok"


def mpris_raise(player: str) -> str:
    """org.mpris.MediaPlayer2.Raise: le pide al player que traiga su ventana al
    frente. Es el reemplazo portable de `niri msg action focus-window`, porque
    ni COSMIC ni Plasma tienen un cliente de consola para enfocar ventanas."""
    try:
        proc = subprocess.run(
            ["busctl", "--user", "call", f"org.mpris.MediaPlayer2.{player}",
             "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2", "Raise"],
            capture_output=True, text=True, timeout=5, env=_playerctl_env(),
        )
    except FileNotFoundError:
        return "busctl no encontrado"
    except subprocess.TimeoutExpired:
        return "timeout en busctl"
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()[:200]
    return "ok"


def player_video_fullscreen(player: str) -> str:
    """Fullscreen del video (no de la ventana): enfoca la ventana del player
    y manda la tecla F (atajo estándar de YouTube/Twitch/mpv/Spotify desktop).
    """
    if active_compositor() == "niri":
        result = niri_focus_player_window(player)
    else:
        result = mpris_raise(player)
    if result != "ok":
        return f"focus: {result}"
    time.sleep(0.15)
    return wtype_key("f")


# ─── Acciones (Fase 3) ───────────────────────────────────────────────────────

def _niri_set_output(name: str, on: bool) -> str:
    rc, stdout, stderr = _niri_run(["output", name, "on" if on else "off"])
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


def _niri_dpms(on: bool) -> str:
    action = "power-on-monitors" if on else "power-off-monitors"
    rc, stdout, stderr = _niri_run(["action", action])
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


# ─── KDE Plasma (kscreen-doctor) ─────────────────────────────────────────────

def _kde_session_env() -> dict:
    """kscreen-doctor habla con KWin por D-Bus; el backend corre como
    servicio systemd sin WAYLAND_DISPLAY/DBUS_SESSION_BUS_ADDRESS propios,
    y sin esto kscreen-doctor aborta (SIGABRT) en vez de fallar limpio."""
    uid = os.getuid()
    runtime_dir = f"/run/user/{uid}"
    env = {**os.environ, "LC_ALL": "C", "XDG_RUNTIME_DIR": runtime_dir}
    bus = Path(runtime_dir) / "bus"
    if bus.exists():
        env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path={bus}"
    displays = sorted(
        (p for p in Path(runtime_dir).glob("wayland-*") if not p.name.endswith(".lock")),
        key=lambda p: p.stat().st_mtime, reverse=True,
    )
    if displays:
        env["WAYLAND_DISPLAY"] = displays[0].name
    return env


def _kscreen_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["kscreen-doctor", *args],
            capture_output=True, text=True, timeout=timeout, env=_kde_session_env(),
        )
    except subprocess.TimeoutExpired:
        return -1, "", "timeout consultando kscreen-doctor"
    except FileNotFoundError:
        return -1, "", "kscreen-doctor no encontrado"
    return proc.returncode, proc.stdout or "", (proc.stderr or "").strip()


def _kde_outputs() -> tuple[list[dict], str | None]:
    rc, stdout, stderr = _kscreen_run(["-j"])
    if rc != 0:
        return [], (stderr or stdout or f"exit {rc}")[:300]
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return [], f"JSON inválido: {e}"
    out = []
    for info in sorted(data.get("outputs", []), key=lambda o: o.get("name") or ""):
        if not info.get("connected"):
            continue
        name = info.get("name")
        if not name:
            continue
        item = {"name": name, "label": name, "on": bool(info.get("enabled"))}
        # Geometría lógica (tamaño del modo / escala), para llevar el cursor al
        # centro de un monitor. kscreen-doctor no expone marca ni modelo.
        size, pos = info.get("size") or {}, info.get("pos") or {}
        scale = float(info.get("scale") or 1) or 1.0
        if item["on"] and size.get("width", -1) > 0:
            item.update(x=int(pos.get("x", 0)), y=int(pos.get("y", 0)),
                        width=int(size["width"] / scale), height=int(size["height"] / scale))
        out.append(item)
    return out, None


def _kde_set_output(name: str, on: bool) -> str:
    action = "enable" if on else "disable"
    rc, stdout, stderr = _kscreen_run([f"output.{name}.{action}"])
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


def _kde_dpms(on: bool) -> str:
    rc, stdout, stderr = _kscreen_run(["--dpms", "on" if on else "off"])
    if rc == 0:
        return "ok"
    return (stderr or stdout or f"exit {rc}")[:300]


# Comandos de ventana en KWin: sus acciones son atajos globales con nombre que
# se disparan por D-Bus (kglobalaccel), sin sintetizar teclas, así que funcionan
# aunque se reasignen las combinaciones en Preferencias del sistema.
_KDE_CMD_SHORTCUTS: dict[str, str] = {
    "fullscreen-window":    "Window Fullscreen",
    "close-window":         "Window Close",
    "maximize-column":      "Window Maximize",
    "focus-column-left":    "Switch Window Left",
    "focus-column-right":   "Switch Window Right",
    "focus-workspace-up":   "Switch One Desktop Up",
    "focus-workspace-down": "Switch One Desktop Down",
    "toggle-overview":      "Overview",
}


def _kde_focus_output(name: str) -> str:
    """Hace activo un monitor (donde abren las ventanas nuevas) con la acción
    nativa de KWin "Switch to Screen N". N es el orden de workspace.screens, que
    sigue la prioridad de las salidas encendidas (1 = principal). No se lleva el
    cursor con ydotool: bajo KWin el salto absoluto pierde eventos y en un
    layout irregular el puntero choca con los bordes."""
    rc, stdout, stderr = _kscreen_run(["-j"])
    if rc != 0:
        return (stderr or stdout or f"exit {rc}")[:300]
    try:
        outputs = json.loads(stdout).get("outputs", [])
    except json.JSONDecodeError as e:
        return f"JSON inválido: {e}"
    target = next((o for o in outputs if o.get("name") == name), None)
    if target is None or not target.get("connected"):
        return f"monitor desconocido: {name}"
    if not target.get("enabled"):
        return f"{name} está apagada"
    orden = sorted((o for o in outputs if o.get("enabled")),
                   key=lambda o: o.get("priority") or 99)
    index = [o["name"] for o in orden].index(name)
    return _kde_shortcut(f"Switch to Screen {index}")


def _kde_shortcut(shortcut: str) -> str:
    try:
        proc = subprocess.run(
            ["busctl", "--user", "call", "org.kde.kglobalaccel", "/component/kwin",
             "org.kde.kglobalaccel.Component", "invokeShortcut", "s", shortcut],
            capture_output=True, text=True, timeout=5, env=_kde_session_env(),
        )
    except FileNotFoundError:
        return "busctl no encontrado"
    except subprocess.TimeoutExpired:
        return "timeout en kglobalaccel"
    if proc.returncode != 0:
        return (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()[:300]
    return "ok"


def _kde_cmd(cmd: str, output: str | None = None) -> str:
    if output:
        result = _kde_focus_output(output)
        if result != "ok" or cmd == "focus-monitor":
            return result
        time.sleep(0.15)
    elif cmd == "focus-monitor":
        return "falta output para focus-monitor"
    shortcut = _KDE_CMD_SHORTCUTS.get(cmd)
    if shortcut is None:
        return f"comando no disponible en KDE Plasma: {cmd}"
    return _kde_shortcut(shortcut)


# ─── COSMIC (cosmic-randr + wlopm) ───────────────────────────────────────────
# cosmic-comp no tiene IPC propio como niri ni D-Bus como KWin: se maneja con
# los clientes Wayland que trae el escritorio.
#   - listar / prender / apagar outputs -> cosmic-randr
#   - DPMS real (power off del panel)   -> wlopm, vía zwlr_output_power_manager_v1
# cosmic-randr NO expone dpms: `disable` saca el output del layout (las
# ventanas se reacomodan), mientras que wlopm apaga la alimentación dejando la
# distribución intacta. Por eso cada una cubre una operación distinta.

def _wayland_session_env() -> dict:
    """cosmic-randr, wlopm y wtype son clientes Wayland: necesitan
    WAYLAND_DISPLAY y XDG_RUNTIME_DIR. El backend corre como servicio systemd y
    no los hereda de la sesión gráfica — mismo problema que kscreen-doctor en
    KDE. No se fija "wayland-1" a mano: se toma el socket más reciente, que es
    el de la sesión viva."""
    uid = os.getuid()
    runtime_dir = f"/run/user/{uid}"
    env = {**os.environ, "LC_ALL": "C", "XDG_RUNTIME_DIR": runtime_dir}
    displays = sorted(
        (p for p in Path(runtime_dir).glob("wayland-*") if not p.name.endswith(".lock")),
        key=lambda p: p.stat().st_mtime, reverse=True,
    )
    if displays:
        env["WAYLAND_DISPLAY"] = displays[0].name
    return env


def _cosmic_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    return _wayland_client_run("cosmic-randr", args, timeout)


def _wlopm_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    return _wayland_client_run("wlopm", args, timeout)


def _wayland_client_run(binary: str, args: list[str], timeout: int) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            [binary, *args],
            capture_output=True, text=True, timeout=timeout, env=_wayland_session_env(),
        )
    except subprocess.TimeoutExpired:
        return -1, "", f"timeout consultando {binary}"
    except FileNotFoundError:
        return -1, "", f"{binary} no encontrado"
    return proc.returncode, proc.stdout or "", (proc.stderr or "").strip()


# `cosmic-randr list` sin --kdl viene con colores ANSI incrustados y es un
# infierno de parsear; el KDL sale limpio y estable.
_COSMIC_OUTPUT_RE = re.compile(r'^output\s+"([^"]+)"\s+enabled=#(true|false)')
_COSMIC_DESC_RE = re.compile(r'make="([^"]*)"\s+model="([^"]*)"')
_COSMIC_POS_RE = re.compile(r'^position\s+(-?\d+)\s+(-?\d+)$')
# Ojo: `adaptive_sync_support` NO matchea acá porque el patrón exige espacio.
_COSMIC_ASYNC_RE = re.compile(r'^adaptive_sync\s+(\S+)$')
_COSMIC_MODE_RE = re.compile(r'^mode\s+(\d+)\s+(\d+)\s+(\d+)\b')


def _cosmic_outputs() -> tuple[list[dict], str | None]:
    """Salidas con geometría. Además de nombre/etiqueta/estado devuelve el modo
    actual y la posición, que en COSMIC hacen falta para dos cosas: reponer el
    modo al reencender una pantalla (cosmic-comp no lo persiste) y ubicar el
    centro de cada monitor para llevarle el cursor."""
    rc, stdout, stderr = _cosmic_run(["list", "--kdl"])
    if rc != 0:
        return [], (stderr or stdout or f"exit {rc}")[:300]
    out: list[dict] = []
    cur: dict | None = None
    for raw in stdout.splitlines():
        line = raw.strip()
        m = _COSMIC_OUTPUT_RE.match(line)
        if m:
            if cur:
                out.append(cur)
            cur = {"name": m.group(1), "label": None, "on": m.group(2) == "true"}
            continue
        if cur is None:
            continue
        if cur["label"] is None:
            d = _COSMIC_DESC_RE.search(line)
            if d:
                make, model = d.group(1).strip(), d.group(2).strip()
                cur["label"] = " ".join(p for p in (make, model) if p) or None
                continue
        pos = _COSMIC_POS_RE.match(line)
        if pos:
            cur["x"], cur["y"] = int(pos.group(1)), int(pos.group(2))
            continue
        vrr = _COSMIC_ASYNC_RE.match(line)
        if vrr:
            cur["adaptive_sync"] = vrr.group(1).strip('"#')
            continue
        if "current=#true" in line:
            mode = _COSMIC_MODE_RE.match(line)
            if mode:
                cur["width"] = int(mode.group(1))
                cur["height"] = int(mode.group(2))
                cur["refresh_mhz"] = int(mode.group(3))
    if cur:
        out.append(cur)
    for o in out:
        o["label"] = o["label"] or o["name"]
    out.sort(key=lambda o: o["name"])
    return out, None


# cosmic-comp NO persiste la configuración de salidas: modo, refresco, VRR y
# posición viven solo en su memoria. Por eso un `disable` + `enable` devolvía la
# pantalla en su modo preferido, y apagar/prender un monitor desde el celular se
# comía los 180 Hz y el FreeSync. Acá se recuerda lo último visto para reponerlo.
_COSMIC_SAVED: dict[str, dict] = {}
_COSMIC_GEOM_KEYS = ("width", "height", "refresh_mhz", "x", "y", "adaptive_sync")


def _cosmic_restore_geometry(name: str) -> None:
    """Reaplica el modo/refresco/VRR/posición recordados al reencender. Best
    effort: si falla, la pantalla queda encendida igual, en su modo preferido."""
    saved = _COSMIC_SAVED.pop(name, None)
    if not saved or not saved.get("width"):
        return
    # cosmic-comp necesita un instante entre el enable y el modeset; sin la
    # espera el `mode` sale con UnknownOutput.
    time.sleep(0.8)
    args = ["mode", name, str(saved["width"]), str(saved["height"])]
    if saved.get("refresh_mhz"):
        args += ["--refresh", f"{saved['refresh_mhz'] / 1000:.3f}"]
    if saved.get("x") is not None:
        args += ["--pos-x", str(saved["x"]), "--pos-y", str(saved.get("y", 0))]
    vrr = saved.get("adaptive_sync")
    if vrr and vrr != "false":
        args += ["--adaptive-sync", vrr]
    rc, stdout, stderr = _cosmic_run(args)
    if rc != 0:
        print(f"cosmic: no pude restaurar el modo de {name}: "
              f"{(stderr or stdout or rc)}", file=sys.stderr)


def _cosmic_set_output(name: str, on: bool) -> str:
    outputs, err = _cosmic_outputs()
    if err:
        return err
    target = next((o for o in outputs if o["name"] == name), None)
    if target is None:
        return f"monitor desconocido: {name}"
    if not on:
        encendidas = [o["name"] for o in outputs if o["on"]]
        # cosmic-comp no admite quedarse sin ninguna salida: apagar la última
        # deja el escritorio inalcanzable desde la propia PC. Para dejar el
        # panel sin imagen sin sacarlo del layout está el DPMS (wlopm).
        if encendidas == [name]:
            return ("es la única pantalla encendida: usa DPMS para apagarle la "
                    "imagen sin sacarla del escritorio")
        if target.get("width"):
            _COSMIC_SAVED[name] = {
                k: target[k] for k in _COSMIC_GEOM_KEYS if k in target
            }
    rc, stdout, stderr = _cosmic_run(["enable" if on else "disable", name])
    if rc != 0:
        return (stderr or stdout or f"exit {rc}")[:300]
    if on:
        _cosmic_restore_geometry(name)
    return "ok"


def _cosmic_dpms(on: bool) -> str:
    """wlopm no acepta comodín en todas las builds, así que se recorre output
    por output. Solo se tocan los habilitados: pedirle power-on a un output
    deshabilitado no lo trae de vuelta al layout y devuelve error."""
    outputs, err = _cosmic_outputs()
    if err:
        return err
    targets = [o["name"] for o in outputs if o["on"]]
    if not targets:
        return "no hay outputs habilitados"
    flag = "--on" if on else "--off"
    errors = []
    for target in targets:
        rc, stdout, stderr = _wlopm_run([flag, target])
        if rc != 0:
            errors.append(f"{target}: {(stderr or stdout or f'exit {rc}').strip()}")
    if errors:
        return "; ".join(errors)[:300]
    return "ok"


# ─── Comandos de ventana / foco en COSMIC ────────────────────────────────────
# cosmic-comp expone zcosmic_toplevel_manager_v1, pero no hay cliente de consola
# que lo hable: no existe equivalente a `niri msg action`. La vía practicable es
# sintetizar los atajos por defecto del escritorio con wtype — el mismo camino
# que ya usa el teclado remoto. Si se reasignan los atajos en Ajustes de COSMIC,
# hay que actualizar este mapa. Los ids son los mismos que en niri para que la
# app no tenga que saber en qué escritorio está.
_COSMIC_CMD_KEYS: dict[str, list[str]] = {
    "fullscreen-window":    ["-M", "logo", "-k", "F11", "-m", "logo"],
    "close-window":         ["-M", "logo", "-k", "q", "-m", "logo"],
    "maximize-column":      ["-M", "logo", "-k", "m", "-m", "logo"],
    "focus-column-left":    ["-M", "logo", "-k", "Left", "-m", "logo"],
    "focus-column-right":   ["-M", "logo", "-k", "Right", "-m", "logo"],
    "focus-workspace-up":   ["-M", "logo", "-M", "ctrl", "-k", "Up",
                             "-m", "ctrl", "-m", "logo"],
    "focus-workspace-down": ["-M", "logo", "-M", "ctrl", "-k", "Down",
                             "-m", "ctrl", "-m", "logo"],
}

# La vista de workspaces de COSMIC es un binario propio (cosmic-workspaces), no
# una acción del compositor. Se guarda el proceso para que el segundo toque la
# cierre en vez de abrir una segunda instancia.
_COSMIC_OVERVIEW: dict[str, object] = {"proc": None}


def _cosmic_toggle_overview() -> str:
    proc = _COSMIC_OVERVIEW.get("proc")
    if proc is not None and proc.poll() is None:
        proc.terminate()
        _COSMIC_OVERVIEW["proc"] = None
        return "ok"
    try:
        _COSMIC_OVERVIEW["proc"] = subprocess.Popen(
            ["cosmic-workspaces"], env=_wayland_session_env(),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except FileNotFoundError:
        return "cosmic-workspaces no encontrado"
    return "ok"


def _cosmic_focus_output(name: str) -> str:
    """En COSMIC la salida activa — donde abren las ventanas nuevas — es la que
    tiene el puntero, y no hay acción tipo `focus-monitor`. Así que se lleva el
    cursor al centro de esa pantalla. Asume escala 1: con escalado el espacio
    absoluto de ydotool y el layout lógico dejan de coincidir."""
    outputs, err = _cosmic_outputs()
    if err:
        return err
    target = next((o for o in outputs if o["name"] == name), None)
    if target is None:
        return f"monitor desconocido: {name}"
    if not target["on"]:
        return f"{name} está apagada"
    if not target.get("width"):
        return f"no pude leer la geometría de {name}"
    import input_control  # perezoso: el módulo solo hace falta para el puntero
    cx = target.get("x", 0) + target["width"] // 2
    cy = target.get("y", 0) + target["height"] // 2
    return input_control.mouse_move_absolute(cx, cy)


def _cosmic_cmd(cmd: str, output: str | None = None) -> str:
    # Los atajos actúan sobre la pantalla activa, así que primero se mueve el
    # foco (o sea, el cursor) si se pidió un monitor concreto.
    if output:
        result = _cosmic_focus_output(output)
        if result != "ok":
            return result
        if cmd == "focus-monitor":
            return "ok"
        time.sleep(0.15)
    elif cmd == "focus-monitor":
        return "falta output para focus-monitor"
    if cmd == "toggle-overview":
        return _cosmic_toggle_overview()
    keys = _COSMIC_CMD_KEYS.get(cmd)
    if keys is None:
        return f"comando no disponible en COSMIC: {cmd}"
    rc, stdout, stderr = _wayland_client_run("wtype", keys, 5)
    if rc != 0:
        return (stderr or stdout or f"exit {rc}")[:300]
    return "ok"


# ─── Hyprland (hyprctl + API Lua hl.*) ───────────────────────────────────────
# Hyprland con config en Lua enruta las MUTACIONES por su intérprete Lua: los
# `hyprctl dispatch`/`keyword` clásicos fallan ("keyword can't work with
# non-legacy parsers. Use eval."). Las CONSULTAS siguen en texto plano
# (`hyprctl -j monitors all`), pero para actuar se usa la API Lua:
#   - dispatchers (dpms, foco, ventanas, workspaces) -> hl.dispatch(hl.dsp.<...>).
#     OJO: `hl.dsp.*` solo CONSTRUYE el dispatcher; sin hl.dispatch() no pasa
#     nada y hyprctl igual responde "ok". Y `hl.dsp.exec_raw` NO es un
#     dispatcher crudo: es el `execr` clásico (lanzar un programa sin reglas).
#   - prender/apagar un output (equivalente a `keyword monitor`) -> hl.monitor{...}.
# El backend corre como servicio systemd sin HYPRLAND_INSTANCE_SIGNATURE; se
# descubre del socket vivo en $XDG_RUNTIME_DIR/hypr/<sig>/ (igual que niri).

def hypr_env() -> dict:
    uid = os.getuid()
    runtime_dir = f"/run/user/{uid}"
    env = {**os.environ, "LC_ALL": "C", "XDG_RUNTIME_DIR": runtime_dir}
    base = Path(runtime_dir) / "hypr"
    try:
        instances = sorted(
            (d for d in base.iterdir() if (d / ".socket.sock").exists()),
            key=lambda d: d.stat().st_mtime, reverse=True,
        )
    except OSError:
        instances = []
    if instances:
        env["HYPRLAND_INSTANCE_SIGNATURE"] = instances[0].name
    return env


def _hypr_run(args: list[str], timeout: int = 5) -> tuple[int, str, str]:
    env = hypr_env()
    if "HYPRLAND_INSTANCE_SIGNATURE" not in env:
        return -1, "", "no encontré socket de Hyprland (¿está corriendo?)"
    try:
        proc = subprocess.run(
            ["hyprctl", *args],
            capture_output=True, text=True, timeout=timeout, env=env,
        )
    except subprocess.TimeoutExpired:
        return -1, "", "timeout consultando hyprctl"
    except FileNotFoundError:
        return -1, "", "hyprctl no encontrado"
    return proc.returncode, proc.stdout or "", (proc.stderr or "").strip()


def _hypr_eval(lua: str, timeout: int = 5) -> tuple[int, str, str]:
    """Ejecuta una línea Lua con `hyprctl eval`. En éxito hyprctl responde 'ok';
    ante un error de Lua devuelve el mensaje del intérprete con exit 0 igual, así
    que cualquier salida distinta de 'ok' se trata como fallo."""
    rc, stdout, stderr = _hypr_run(["eval", lua], timeout)
    if rc != 0:
        return rc, stdout, (stderr or stdout)
    out = (stdout or "").strip()
    if out.lower() == "ok":
        return 0, out, ""
    # Los errores de dispatchers llegan como 'warning: =[C]:-1: Bad workspace';
    # a la app le sirve solo el mensaje.
    out = re.sub(r"^(error|warning|info):\s*(=\[C\]:-?\d+:\s*)?", "", out)
    return 1, stdout, (out or stderr or "error de hyprctl eval")


def _lua_str(s: str) -> str:
    """Literal de string Lua. Solo se usa con nombres de monitor ya validados
    contra `hyprctl monitors`, pero se escapa igual por si acaso."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _hypr_dispatch(dsp: str) -> str:
    """Ejecuta un dispatcher de la API Lua, p. ej. 'hl.dsp.window.close()'. La
    expresión sale de la whitelist del backend, nunca de texto del cliente.
    Hyprland devuelve el error en texto ('warning: Bad workspace', 'error: …'),
    que _hypr_eval ya trata como fallo."""
    rc, _stdout, stderr = _hypr_eval(f"hl.dispatch({dsp})")
    if rc == 0:
        return "ok"
    return (stderr or f"exit {rc}")[:300]


def _hypr_outputs() -> tuple[list[dict], str | None]:
    rc, stdout, stderr = _hypr_run(["-j", "monitors", "all"])
    if rc != 0:
        return [], (stderr or stdout or f"exit {rc}")[:300]
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return [], f"JSON inválido: {e}"
    out = []
    for info in sorted(data, key=lambda m: m.get("name") or ""):
        name = info.get("name")
        if not name:
            continue
        make = (info.get("make") or "").strip()
        model = (info.get("model") or "").strip()
        label = " ".join(p for p in (make, model) if p) or name
        on = not info.get("disabled", False)
        item = {"name": name, "label": label, "on": on}
        # Geometría lógica (tamaño del modo / escala), para llevar el cursor al
        # centro de un monitor en escenas y foco, igual que en KDE/COSMIC.
        scale = float(info.get("scale") or 1) or 1.0
        w, h = int(info.get("width") or 0), int(info.get("height") or 0)
        if on and w:
            item.update(x=int(info.get("x", 0)), y=int(info.get("y", 0)),
                        width=int(w / scale), height=int(h / scale))
        out.append(item)
    return out, None


# Geometría (modo + posición + escala) guardada antes de apagar un output, para
# reponerla tal cual al reencenderlo. Sin esto, `disabled = false` deja que
# Hyprland reposicione en automático y se pierde el layout del usuario (el
# monitor reaparecía pegado al borde derecho en vez de su sitio). Mismo patrón
# que `_COSMIC_SAVED`: vive en memoria, así que un reinicio del backend entre el
# apagado y el encendido cae al reposicionado automático (aceptable y raro).
_HYPR_SAVED: dict[str, dict] = {}


def _hypr_geometry(name: str) -> dict | None:
    """Modo/posición/escala actuales de `name` en la sintaxis que acepta
    hl.monitor (mode "WxH@R", position "XxY", scale número), o None si no se
    puede leer (output apagado o inexistente)."""
    rc, stdout, _ = _hypr_run(["-j", "monitors", "all"])
    if rc != 0:
        return None
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError:
        return None
    info = next((m for m in data if m.get("name") == name), None)
    if not info or info.get("disabled") or not info.get("width"):
        return None
    return {
        "mode": f'{int(info["width"])}x{int(info["height"])}'
                f'@{float(info.get("refreshRate") or 0):.5f}',
        "position": f'{int(info.get("x", 0))}x{int(info.get("y", 0))}',
        "scale": info.get("scale") or 1,
    }


def _hypr_set_output(name: str, on: bool) -> str:
    outputs, err = _hypr_outputs()
    if err:
        return err
    target = next((o for o in outputs if o["name"] == name), None)
    if target is None:
        return f"monitor desconocido: {name}"
    if not on:
        encendidas = [o["name"] for o in outputs if o["on"]]
        # Como en COSMIC: apagar la última salida deja el escritorio inalcanzable
        # desde la propia PC. Para dejar el panel sin imagen sin sacarlo del
        # layout está el DPMS.
        if encendidas == [name]:
            return ("es la única pantalla encendida: usa DPMS para apagarle la "
                    "imagen sin sacarla del escritorio")
        # Guardamos la geometría antes de apagar, para reponerla intacta luego.
        geo = _hypr_geometry(name)
        if geo:
            _HYPR_SAVED[name] = geo
        lua = f'hl.monitor({{ output = "{name}", disabled = true }})'
    else:
        # Al reencender restauramos modo/posición/escala guardados; así el
        # monitor vuelve a su sitio en vez de que Hyprland lo reubique solo. Sin
        # geometría guardada (p. ej. tras reiniciar el backend) cae a
        # `disabled = false`, que al menos lo reactiva con su config declarada.
        saved = _HYPR_SAVED.pop(name, None)
        if saved:
            # disabled=false va junto con la geometría: fijar solo mode/pos/scale
            # NO reactiva un output apagado (se queda disabled); hace falta pedir
            # explícitamente disabled=false en la misma llamada.
            lua = (f'hl.monitor({{ output = "{name}", mode = "{saved["mode"]}", '
                   f'position = "{saved["position"]}", scale = "{saved["scale"]}", '
                   f'disabled = false }})')
        else:
            lua = f'hl.monitor({{ output = "{name}", disabled = false }})'
    rc, _stdout, stderr = _hypr_eval(lua)
    if rc == 0:
        if on:
            # Con la geometría ya aplicada, la regla persistida la fija tal cual.
            geo = _hypr_geometry(name)
            if geo:
                lua = (f'hl.monitor({{ output = "{name}", mode = "{geo["mode"]}", '
                       f'position = "{geo["position"]}", scale = "{geo["scale"]}", '
                       f'disabled = false }})')
        _hypr_persist_rule(name, lua)
        return "ok"
    return (stderr or f"exit {rc}")[:300]


def _hypr_overrides_path() -> Path:
    return Path(f"/run/user/{os.getuid()}") / "apppanda" / "hyprland.lua"


def _hypr_persist_rule(name: str, lua: str) -> None:
    """Guarda la última regla aplicada a `name` en $XDG_RUNTIME_DIR/apppanda/
    hyprland.lua (una línea por output). `hyprctl eval` solo cambia el estado
    en caliente: cualquier `hyprctl reload` (p. ej. el que lanza un cambio de
    paleta al aparecer un monitor nuevo) vuelve a aplicar las reglas del config
    y deshace lo que hizo la app; la TV se apagaba sola a los 3 s de encenderla.
    Si el config de Hyprland hace dofile de este archivo al final de sus reglas
    de monitores, el estado elegido desde la app sobrevive a los reloads. Vive
    en /run: al reiniciar desaparece y manda el config."""
    path = _hypr_overrides_path()
    marker = f'output = "{name}"'
    try:
        lines = path.read_text().splitlines()
    except OSError:
        lines = []
    lines = [ln for ln in lines if marker not in ln and ln.strip()]
    lines.append(lua)
    try:
        path.parent.mkdir(mode=0o700, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text("\n".join(lines) + "\n")
        tmp.replace(path)
    except OSError as e:
        print(f"hypr: could not persist {path}: {e}", file=sys.stderr)


def _hypr_dpms(on: bool) -> str:
    return _hypr_dispatch(f'hl.dsp.dpms({{ action = "{"on" if on else "off"}" }})')


# Comandos de ventana/foco/workspace de Hyprland -> dispatcher de la API Lua.
# Siguen los atajos de la config (binds.lua): 'Maximizar' es el fullscreen modo
# 1 (respeta la barra) y el modo 0 es pantalla completa real; los workspaces
# son relativos al monitor (m-1/m+1) y 'emptym' salta al primero vacío de ese
# monitor. Los ids viejos de niri (focus-column-*, focus-workspace-*,
# maximize-column) quedan como alias para versiones anteriores de la app.
_HYPR_CMD_MAP: dict[str, str] = {
    # ventana
    "fullscreen-window":   "hl.dsp.window.fullscreen()",
    "maximize-window":     "hl.dsp.window.fullscreen({ mode = 1 })",
    "toggle-floating":     'hl.dsp.window.float({ action = "toggle" })',
    "toggle-split":        'hl.dsp.layout("togglesplit")',
    "pin-window":          "hl.dsp.window.pin()",
    "close-window":        "hl.dsp.window.close()",
    # foco
    "focus-left":          'hl.dsp.focus({ direction = "left" })',
    "focus-right":         'hl.dsp.focus({ direction = "right" })',
    "focus-up":            'hl.dsp.focus({ direction = "up" })',
    "focus-down":          'hl.dsp.focus({ direction = "down" })',
    "cycle-window":        "hl.dsp.window.cycle_next()",
    # workspaces del monitor
    "workspace-prev":      'hl.dsp.focus({ workspace = "m-1" })',
    "workspace-next":      'hl.dsp.focus({ workspace = "m+1" })',
    "workspace-empty":     'hl.dsp.focus({ workspace = "emptym" })',
    "toggle-special":      "hl.dsp.workspace.toggle_special()",
    # mover la ventana activa
    "move-workspace-prev": 'hl.dsp.window.move({ workspace = "m-1" })',
    "move-workspace-next": 'hl.dsp.window.move({ workspace = "m+1" })',
    "move-to-special":     'hl.dsp.window.move({ workspace = "special" })',
    "move-monitor-next":   'hl.dsp.window.move({ monitor = "+1" })',
    # alias de la app anterior (ids de niri)
    "maximize-column":     "hl.dsp.window.fullscreen({ mode = 1 })",
    "focus-column-left":   'hl.dsp.focus({ direction = "left" })',
    "focus-column-right":  'hl.dsp.focus({ direction = "right" })',
    "focus-workspace-up":  'hl.dsp.focus({ workspace = "m-1" })',
    "focus-workspace-down": 'hl.dsp.focus({ workspace = "m+1" })',
}

# Paneles de Noctalia (la shell de la torre y el laptop). Se lanzan con el exec
# de Hyprland para que hereden el entorno de la sesión gráfica; solo se ofrecen
# si `noctalia` está instalado.
_HYPR_NOCTALIA_CMDS: dict[str, str] = {
    "window-switcher": "noctalia msg window-switcher",
    "launcher":        "noctalia msg panel-toggle launcher",
    "control-center":  "noctalia msg panel-toggle control-center",
}


def _hypr_cmd(cmd: str, output: str | None = None) -> str:
    # Los dispatchers actúan sobre el monitor/ventana enfocado, así que si se
    # pidió un monitor concreto se enfoca primero (como en niri).
    if output:
        valid = {o["name"] for o in _hypr_outputs()[0]}
        if output not in valid:
            return f"monitor desconocido: {output}"
        result = _hypr_dispatch(f"hl.dsp.focus({{ monitor = {_lua_str(output)} }})")
        if result != "ok" or cmd == "focus-monitor":
            return result
        time.sleep(0.15)
    elif cmd == "focus-monitor":
        return "falta output para focus-monitor"
    dsp = _HYPR_CMD_MAP.get(cmd)
    if dsp is None and cmd in _HYPR_NOCTALIA_CMDS and shutil.which("noctalia"):
        dsp = f'hl.dsp.exec_cmd("{_HYPR_NOCTALIA_CMDS[cmd]}")'
    if dsp is None:
        return f"comando no disponible en Hyprland: {cmd}"
    return _hypr_dispatch(dsp)


def _hypr_cmd_keys() -> list[str]:
    keys = [*_HYPR_CMD_MAP, "focus-monitor"]
    if shutil.which("noctalia"):
        keys += list(_HYPR_NOCTALIA_CMDS)
    return keys


# ─── Pantallas (dispatch niri / KDE / COSMIC / Hyprland) ──────────────────────
# Punto único que usa el resto del backend para listar/prender/apagar
# monitores. Elige backend según el compositor activo en este momento, así
# la misma app funciona en niri, Plasma, COSMIC o Hyprland sin reconfigurar nada.

_NO_COMPOSITOR = "no se detectó niri, KDE Plasma, COSMIC ni Hyprland corriendo"


def screen_outputs() -> tuple[list[dict], str | None]:
    comp = active_compositor()
    if comp == "kde":
        return _kde_outputs()
    if comp == "niri":
        return _niri_outputs()
    if comp == "cosmic":
        return _cosmic_outputs()
    if comp == "hyprland":
        return _hypr_outputs()
    return [], _NO_COMPOSITOR


def screen_set_output(name: str, on: bool) -> str:
    comp = active_compositor()
    if comp == "kde":
        return _kde_set_output(name, on)
    if comp == "niri":
        return _niri_set_output(name, on)
    if comp == "cosmic":
        return _cosmic_set_output(name, on)
    if comp == "hyprland":
        return _hypr_set_output(name, on)
    return _NO_COMPOSITOR


def screen_dpms(on: bool) -> str:
    comp = active_compositor()
    if comp == "kde":
        return _kde_dpms(on)
    if comp == "niri":
        return _niri_dpms(on)
    if comp == "cosmic":
        return _cosmic_dpms(on)
    if comp == "hyprland":
        return _hypr_dpms(on)
    return _NO_COMPOSITOR


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
        valid = {o["name"] for o in _niri_outputs()[0]}
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


def wm_cmd(cmd: str, output: str | None = None) -> str:
    """Comandos de ventana/foco/workspace, con los mismos ids en todos los
    escritorios: la app manda 'fullscreen-window' y acá se traduce a la acción
    de niri, al atajo global de KWin o al atajo de COSMIC según lo que esté
    corriendo."""
    comp = active_compositor()
    if comp == "niri":
        return niri_cmd(cmd, output)
    if comp == "cosmic":
        return _cosmic_cmd(cmd, output)
    if comp == "kde":
        return _kde_cmd(cmd, output)
    if comp == "hyprland":
        return _hypr_cmd(cmd, output)
    return _NO_COMPOSITOR


def wm_cmds() -> list[str]:
    """Ids que soporta el compositor activo, para que la app muestre solo los
    botones que van a funcionar (en COSMIC, por ejemplo, no hay 'media-workspace',
    que era un spawn propio de la config de niri)."""
    comp = active_compositor()
    if comp == "niri":
        return sorted(NIRI_CMD_MAP)
    if comp == "cosmic":
        return sorted([*_COSMIC_CMD_KEYS, "toggle-overview", "focus-monitor"])
    if comp == "kde":
        return sorted([*_KDE_CMD_SHORTCUTS, "focus-monitor"])
    if comp == "hyprland":
        return sorted(_hypr_cmd_keys())
    return []


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


_MAC_RE = re.compile(r"^[0-9A-Fa-f]{2}([:.\-]?[0-9A-Fa-f]{2}){5}$")


def _ipv4_broadcasts() -> list[str]:
    """Broadcast dirigido de cada red IPv4 con salida real (sin docker/tailscale)."""
    out = run(["ip", "-4", "-o", "addr", "show", "scope", "global"], timeout=3)
    res = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 4 or parts[1].startswith(("docker", "br-", "veth", "tailscale", "virbr")):
            continue
        if "brd" in parts:
            res.append(parts[parts.index("brd") + 1])
    return res


def wol_send(target: str, cfg: dict, broadcast: str = "") -> str:
    """Manda el magic packet desde la LAN de este PC (sirve de relay para
    encender a otro que está apagado). [target] es una MAC o un alias de
    [net.wake]. Sin binarios externos: UDP broadcast a los puertos 9 y 7."""
    import socket
    mapping = (cfg.get("net") or {}).get("wake") or {}
    mac = mapping.get(target, target).strip()
    if not _MAC_RE.match(mac):
        return f"MAC inválida o alias no mapeado: {target}"
    raw = bytes.fromhex(re.sub(r"[^0-9A-Fa-f]", "", mac))
    packet = b"\xff" * 6 + raw * 16
    dests = [broadcast.strip()] if broadcast.strip() else []
    dests += _ipv4_broadcasts() + ["255.255.255.255"]
    sent, errors = 0, []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for dest in dict.fromkeys(dests):
            for port in (9, 7):
                try:
                    sock.sendto(packet, (dest, port))
                    sent += 1
                except OSError as e:
                    errors.append(f"{dest}:{port} {e.strerror or e}")
    if sent:
        return "ok"
    return ("; ".join(errors) or "no hay red IPv4 para mandar el paquete")[:200]


def wol_info() -> dict:
    """Interfaces físicas con su MAC, para que la app configure el encendido
    de este PC mientras está prendido. Marca la que lleva la ruta por defecto."""
    default_dev = ""
    route = run(["ip", "-4", "route", "show", "default"], timeout=3).split()
    if "dev" in route:
        default_dev = route[route.index("dev") + 1]
    ifaces = []
    for dev in sorted(os.listdir("/sys/class/net")):
        base = Path("/sys/class/net") / dev
        if not (base / "device").exists() or (base / "wireless").exists():
            continue  # solo cableadas reales: WoL por wifi casi nunca funciona
        try:
            mac = (base / "address").read_text().strip()
            operstate = (base / "operstate").read_text().strip()
        except OSError:
            continue
        ifaces.append({
            "name": dev, "mac": mac, "up": operstate == "up",
            "default": dev == default_dev,
        })
    ifaces.sort(key=lambda i: (not i["default"], not i["up"], i["name"]))
    return {"interfaces": ifaces, "broadcasts": _ipv4_broadcasts()}


def steam_launch(appid: str, cfg: dict) -> str:
    # Los appid de Steam son siempre numéricos. A diferencia de
    # docker_action (que regex-valida el nombre), esto no se chequeaba: un
    # valor no numérico se metía igual en la URI y producía un "steam://
    # rungameid/<basura>" roto en vez de un error claro.
    if not (appid or "").isdigit():
        return "appid inválido"
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


def steam_running(cfg: dict) -> dict | None:
    """Juego de Steam corriendo ahora, leyendo RunningAppID del registry.vdf.
    Devuelve {appid, name} o None si no hay ninguno."""
    candidates = [
        Path.home() / ".steam/registry.vdf",
        Path.home() / ".steam/steam/registry.vdf",
    ]
    appid = None
    for reg in candidates:
        if not reg.exists():
            continue
        try:
            text = reg.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        m = re.search(r'"RunningAppID"\s+"(\d+)"', text)
        if m:
            appid = m.group(1)
            break
    if not appid or appid == "0":
        return None
    name = next(
        (g["name"] for g in steam_games(cfg) if g["appid"] == appid), None,
    )
    return {"appid": appid, "name": name or f"AppID {appid}"}


def steam_close(cfg: dict) -> str:
    """Cierra el juego en curso: manda SIGTERM a los procesos cuyo cmdline
    contiene `AppId=<appid>` (el reaper de SteamLaunch y sus hijos directos)."""
    running = steam_running(cfg)
    if not running:
        return "no hay juego corriendo"
    appid = running["appid"]
    needle = f"AppId={appid}"
    killed = 0
    for proc_dir in Path("/proc").glob("[0-9]*"):
        try:
            cmdline = (proc_dir / "cmdline").read_bytes()
        except OSError:
            continue
        if needle.encode() not in cmdline:
            continue
        try:
            pid = int(proc_dir.name)
            os.kill(pid, 15)  # SIGTERM
            killed += 1
        except (ValueError, ProcessLookupError, PermissionError):
            continue
    if killed == 0:
        return f"no encontré proceso para AppID {appid}"
    return "ok"


# ─── Escenas (presets de monitores + audio) ───────────────────────────────────

def scenes_list(cfg: dict) -> list[dict]:
    scenes = cfg.get("scenes") or {}
    out = []
    for name, spec in scenes.items():
        if isinstance(spec, dict):
            out.append({"name": name, "label": spec.get("label", name)})
    return out


def scene_apply(cfg: dict, name: str) -> str:
    """Aplica una escena: apaga/enciende outputs, fija foco y sink de audio.
    Compone las acciones ya existentes. Devuelve 'ok' o los errores juntos."""
    spec = (cfg.get("scenes") or {}).get(name)
    if not isinstance(spec, dict):
        return f"escena '{name}' no configurada"
    errors = []
    # Apagar primero (por si se libera una salida), luego encender.
    for o in spec.get("outputs_off", []) or []:
        r = screen_set_output(o, False)
        if r != "ok":
            errors.append(f"off {o}: {r}")
    for o in spec.get("outputs_on", []) or []:
        r = screen_set_output(o, True)
        if r != "ok":
            errors.append(f"on {o}: {r}")
    focus = spec.get("focus")
    if focus:
        # "Fijar" un monitor: en niri, KDE Plasma y Hyprland es una acción del
        # compositor; en COSMIC se traduce a llevar el cursor ahí (la pantalla
        # activa es la que tiene el puntero).
        comp = active_compositor()
        if comp == "niri":
            rc, _, stderr = _niri_run(["action", "focus-monitor", focus])
            if rc != 0:
                errors.append(f"focus {focus}: {(stderr or rc)}")
        elif comp == "hyprland":
            result = _hypr_dispatch(f"hl.dsp.focus({{ monitor = {_lua_str(focus)} }})")
            if result != "ok":
                errors.append(f"focus {focus}: {result}")
        elif comp in ("cosmic", "kde"):
            result = (_cosmic_focus_output if comp == "cosmic" else _kde_focus_output)(focus)
            if result != "ok":
                errors.append(f"focus {focus}: {result}")
    sink = spec.get("sink")
    if sink:
        r = audio_set_sink(sink)
        if r != "ok":
            errors.append(f"sink: {r}")
    return "; ".join(errors)[:300] if errors else "ok"
