"""Audio (pactl), micrófono, MPRIS (playerctl) y portapapeles (wl-clipboard).

Separado de apppanda-backend.py; ver ese archivo para el diseño general.
"""

import json
import os
import subprocess
import sys


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


def audio_master() -> dict:
    """Volumen (promedio de canales) y mute del sink por defecto."""
    default, err = audio_default_sink()
    if err or not default:
        return {"volume_pct": None, "muted": None, "sink": "", "error": err}
    rc, stdout, stderr = _pactl_run(["-f", "json", "list", "sinks"])
    if rc != 0:
        return {"volume_pct": None, "muted": None, "sink": default,
                "error": (stderr or stdout or f"exit {rc}")[:200]}
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return {"volume_pct": None, "muted": None, "sink": default,
                "error": f"JSON inválido: {e}"}
    for s in data:
        if s.get("name") != default:
            continue
        vals = []
        for ch in (s.get("volume") or {}).values():
            if isinstance(ch, dict):
                pct = str(ch.get("value_percent", "")).rstrip("%").strip()
                try:
                    vals.append(float(pct))
                except ValueError:
                    pass
        return {
            "volume_pct": round(sum(vals) / len(vals)) if vals else None,
            "muted": bool(s.get("mute")),
            "sink": default,
            "error": None,
        }
    return {"volume_pct": None, "muted": None, "sink": default,
            "error": "sink por defecto no encontrado"}


def audio_set_volume(pct: int) -> str:
    """Fija el volumen del sink por defecto. Clampa a 0–150 %."""
    try:
        pct = max(0, min(150, int(pct)))
    except (TypeError, ValueError):
        return f"pct inválido: {pct!r}"
    rc, _, stderr = _pactl_run(["set-sink-volume", "@DEFAULT_SINK@", f"{pct}%"])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


def audio_set_mute(state: str) -> str:
    """state: on | off | toggle (mute del sink por defecto)."""
    arg = {"on": "1", "off": "0", "toggle": "toggle"}.get(state)
    if arg is None:
        return f"estado inválido: {state}"
    rc, _, stderr = _pactl_run(["set-sink-mute", "@DEFAULT_SINK@", arg])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


def _volume_avg_pct(vol: dict) -> int | None:
    vals = []
    for ch in (vol or {}).values():
        if isinstance(ch, dict):
            pct = str(ch.get("value_percent", "")).rstrip("%").strip()
            try:
                vals.append(float(pct))
            except ValueError:
                pass
    return round(sum(vals) / len(vals)) if vals else None


def audio_app_inputs() -> tuple[list[dict], str | None]:
    """Streams de audio por aplicación (sink-inputs): id, etiqueta, vol %, mute."""
    rc, stdout, stderr = _pactl_run(["-f", "json", "list", "sink-inputs"])
    if rc != 0:
        return [], (stderr or stdout or f"exit {rc}")[:200]
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return [], f"JSON inválido: {e}"
    out = []
    for si in data:
        props = si.get("properties") or {}
        label = (
            (props.get("application.name") or "").strip()
            or (props.get("media.name") or "").strip()
            or (props.get("application.process.binary") or "").strip()
            or f"stream {si.get('index')}"
        )
        out.append({
            "id": si.get("index"),
            "label": label,
            "media": (props.get("media.name") or "").strip(),
            "volume_pct": _volume_avg_pct(si.get("volume") or {}),
            "muted": bool(si.get("mute")),
        })
    return out, None


def audio_set_app_volume(input_id: str, pct: int) -> str:
    if not str(input_id).isdigit():
        return f"id inválido: {input_id}"
    try:
        pct = max(0, min(150, int(pct)))
    except (TypeError, ValueError):
        return f"pct inválido: {pct!r}"
    rc, _, stderr = _pactl_run(
        ["set-sink-input-volume", str(input_id), f"{pct}%"])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


def audio_set_app_mute(input_id: str, state: str) -> str:
    if not str(input_id).isdigit():
        return f"id inválido: {input_id}"
    arg = {"on": "1", "off": "0", "toggle": "toggle"}.get(state)
    if arg is None:
        return f"estado inválido: {state}"
    rc, _, stderr = _pactl_run(["set-sink-input-mute", str(input_id), arg])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


# ─── Micrófono (source por defecto) ───────────────────────────────────────────

def audio_source() -> dict:
    """Volumen y mute de la fuente (micrófono) por defecto."""
    rc, stdout, stderr = _pactl_run(["get-default-source"])
    if rc != 0:
        return {"volume_pct": None, "muted": None, "source": "",
                "error": (stderr or stdout or f"exit {rc}")[:200]}
    default = stdout.strip()
    rc, stdout, stderr = _pactl_run(["-f", "json", "list", "sources"])
    if rc != 0:
        return {"volume_pct": None, "muted": None, "source": default,
                "error": (stderr or stdout or f"exit {rc}")[:200]}
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError as e:
        return {"volume_pct": None, "muted": None, "source": default,
                "error": f"JSON inválido: {e}"}
    for s in data:
        if s.get("name") != default:
            continue
        return {
            "volume_pct": _volume_avg_pct(s.get("volume") or {}),
            "muted": bool(s.get("mute")),
            "source": default,
            "error": None,
        }
    return {"volume_pct": None, "muted": None, "source": default,
            "error": "fuente por defecto no encontrada"}


def audio_set_mic_mute(state: str) -> str:
    arg = {"on": "1", "off": "0", "toggle": "toggle"}.get(state)
    if arg is None:
        return f"estado inválido: {state}"
    rc, _, stderr = _pactl_run(["set-source-mute", "@DEFAULT_SOURCE@", arg])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


def audio_set_mic_volume(pct: int) -> str:
    try:
        pct = max(0, min(150, int(pct)))
    except (TypeError, ValueError):
        return f"pct inválido: {pct!r}"
    rc, _, stderr = _pactl_run(["set-source-volume", "@DEFAULT_SOURCE@", f"{pct}%"])
    if rc != 0:
        return (stderr or f"exit {rc}")[:200]
    return "ok"


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


# ─── Portapapeles (wl-clipboard) ──────────────────────────────────────────────

def _wayland_env() -> dict:
    uid = os.getuid()
    return {
        **os.environ,
        "XDG_RUNTIME_DIR": f"/run/user/{uid}",
        "WAYLAND_DISPLAY": "wayland-1",
    }


def clipboard_get(max_bytes: int = 100_000) -> dict:
    """Lee el portapapeles del PC vía wl-paste. Solo texto UTF-8."""
    try:
        proc = subprocess.run(
            ["wl-paste", "--no-newline", "--type", "text"],
            capture_output=True, timeout=5, env=_wayland_env(),
        )
    except FileNotFoundError:
        return {"text": "", "error": "wl-paste no instalado (wl-clipboard)"}
    except subprocess.TimeoutExpired:
        return {"text": "", "error": "timeout"}
    if proc.returncode != 0:
        err = (proc.stderr.decode("utf-8", "replace") or "").strip().lower()
        # wl-paste sale != 0 cuando el portapapeles está vacío: no es error.
        if not err or "empty" in err or "no selection" in err:
            return {"text": "", "error": None}
        return {"text": "", "error": err[:200]}
    try:
        text = proc.stdout[:max_bytes].decode("utf-8")
    except UnicodeDecodeError:
        return {"text": "", "error": "el portapapeles no es texto UTF-8"}
    return {"text": text, "error": None}


def clipboard_set(text: str) -> str:
    """Escribe texto al portapapeles del PC vía wl-copy.

    wl-copy forkea un proceso que sigue vivo para servir el contenido; con
    stdout/stderr a DEVNULL evitamos que subprocess.run quede esperando EOF
    en el pipe que el hijo mantiene abierto.
    """
    try:
        proc = subprocess.run(
            ["wl-copy"], input=text.encode("utf-8"),
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=5, env=_wayland_env(),
        )
    except FileNotFoundError:
        return "wl-copy no instalado (wl-clipboard)"
    except subprocess.TimeoutExpired:
        return "timeout"
    if proc.returncode != 0:
        return f"exit {proc.returncode}"
    return "ok"
