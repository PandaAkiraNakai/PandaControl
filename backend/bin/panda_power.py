"""Inhibir suspensión y caffeine (inhibidor de idle con temporizador).

Separado de apppanda-backend.py; ver ese archivo para el diseño general.
"""

import json
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

from panda_screens import _kde_session_env


# ─── Inhibir suspensión (systemd-inhibit) ─────────────────────────────────────

_INHIBITOR: subprocess.Popen | None = None
_INHIBITOR_LOCK = threading.Lock()


def power_inhibit(on: bool) -> str:
    """Activa/desactiva un bloqueo de suspensión/idle. Mantiene vivo un
    `systemd-inhibit ... sleep infinity`; matarlo libera el bloqueo."""
    global _INHIBITOR
    with _INHIBITOR_LOCK:
        running = _INHIBITOR is not None and _INHIBITOR.poll() is None
        if on:
            if running:
                return "ok"
            try:
                _INHIBITOR = subprocess.Popen(
                    ["systemd-inhibit", "--what=sleep:idle",
                     "--who=Panda Control", "--why=Sergio lo pidió desde el celu",
                     "--mode=block", "sleep", "infinity"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            except FileNotFoundError:
                return "systemd-inhibit no encontrado"
            return "ok"
        else:
            if running:
                _INHIBITOR.terminate()
            _INHIBITOR = None
            return "ok"


def power_inhibit_state() -> bool:
    with _INHIBITOR_LOCK:
        return _INHIBITOR is not None and _INHIBITOR.poll() is None


# ─── Caffeine (inhibidor de idle, con temporizador) ───────────────────────────
# Si corre Noctalia se usa SU caffeine (`noctalia msg caffeine-*`), así el
# widget de la barra y la app muestran lo mismo. Sin Noctalia, un
# `systemd-inhibit --what=idle` propio. El estado se lee de logind
# (ListInhibitors): refleja también lo que se active/apague desde el PC.
# La hora de apagado se guarda en el directorio de estado del backend
# (caffeine.json): un reinicio del daemon retoma el temporizador en vez de
# dejar caffeine encendido sin límite.

_CAFFEINE_LOCK = threading.Lock()
_CAFFEINE_UNTIL: float | None = None          # apagado automático (epoch)
_CAFFEINE_TIMER: threading.Timer | None = None
_CAFFEINE_PROC: subprocess.Popen | None = None  # solo sin Noctalia
_CAFFEINE_MAX_MIN = 24 * 60
_CAFFEINE_STATE: Path | None = None             # lo fija caffeine_restore()


def _noctalia_msg(*args: str) -> tuple[int, str]:
    if not shutil.which("noctalia"):
        return 127, "noctalia no instalado"
    try:
        proc = subprocess.run(
            ["noctalia", "msg", *args], capture_output=True, text=True,
            timeout=5, env=_kde_session_env(),
        )
    except subprocess.TimeoutExpired:
        return 1, "timeout hablando con noctalia"
    return proc.returncode, (proc.stdout or proc.stderr or "").strip()


def _caffeine_backend() -> str:
    return "noctalia" if _noctalia_msg("status")[0] == 0 else "systemd"


def _idle_inhibitors() -> list[tuple[str, str, str]]:
    """(who, why, mode) de los inhibidores de idle de este usuario."""
    try:
        proc = subprocess.run(
            ["busctl", "--json=short", "call", "org.freedesktop.login1",
             "/org/freedesktop/login1", "org.freedesktop.login1.Manager",
             "ListInhibitors"],
            capture_output=True, text=True, timeout=5,
        )
        rows = json.loads(proc.stdout)["data"][0]
    except (subprocess.TimeoutExpired, FileNotFoundError, ValueError, KeyError, IndexError):
        return []
    uid = os.getuid()
    return [(who, why, mode) for what, who, why, mode, r_uid, _pid in rows
            if "idle" in what.split(":") and r_uid == uid]


def _caffeine_active(backend: str) -> bool:
    if backend == "noctalia":
        return any(who == "noctalia" and why == "Caffeine"
                   for who, why, _m in _idle_inhibitors())
    return _CAFFEINE_PROC is not None and _CAFFEINE_PROC.poll() is None


def _caffeine_apply(on: bool, backend: str) -> str:
    global _CAFFEINE_PROC
    if backend == "noctalia":
        rc, out = _noctalia_msg("caffeine-enable" if on else "caffeine-disable")
        return "ok" if rc == 0 else (out or f"exit {rc}")[:300]
    running = _CAFFEINE_PROC is not None and _CAFFEINE_PROC.poll() is None
    if on and not running:
        try:
            _CAFFEINE_PROC = subprocess.Popen(
                ["systemd-inhibit", "--what=idle", "--who=Panda Control",
                 "--why=Caffeine", "--mode=block", "sleep", "infinity"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        except FileNotFoundError:
            return "systemd-inhibit no encontrado"
    elif not on and running:
        _CAFFEINE_PROC.terminate()
        _CAFFEINE_PROC = None
    return "ok"


def _caffeine_save(until: float | None) -> None:
    if _CAFFEINE_STATE is None:
        return
    try:
        if until is None:
            _CAFFEINE_STATE.unlink(missing_ok=True)
        else:
            tmp = _CAFFEINE_STATE.with_suffix(".tmp")
            tmp.write_text(json.dumps({"until": until}))
            tmp.replace(_CAFFEINE_STATE)
    except OSError as e:
        print(f"caffeine: no pude guardar {_CAFFEINE_STATE}: {e}", file=sys.stderr)


def _caffeine_schedule(until: float) -> None:
    global _CAFFEINE_UNTIL, _CAFFEINE_TIMER
    timer = threading.Timer(max(0.0, until - time.time()), _caffeine_expire, args=(until,))
    timer.daemon = True
    timer.start()
    _CAFFEINE_UNTIL, _CAFFEINE_TIMER = until, timer
    _caffeine_save(until)


def _caffeine_cancel_timer() -> None:
    global _CAFFEINE_UNTIL, _CAFFEINE_TIMER
    if _CAFFEINE_TIMER is not None:
        _CAFFEINE_TIMER.cancel()
    _CAFFEINE_TIMER = None
    _CAFFEINE_UNTIL = None
    _caffeine_save(None)


def _caffeine_expire(until: float) -> None:
    with _CAFFEINE_LOCK:
        if _CAFFEINE_UNTIL != until:  # se reprogramó o se apagó a mano
            return
        _caffeine_cancel_timer()
        _caffeine_apply(False, _caffeine_backend())


def caffeine_set(on: bool, minutes: int | None = None) -> str:
    """Enciende (opcionalmente por `minutes`) o apaga caffeine."""
    if minutes is not None and not 1 <= minutes <= _CAFFEINE_MAX_MIN:
        return f"minutes fuera de rango (1-{_CAFFEINE_MAX_MIN})"
    with _CAFFEINE_LOCK:
        _caffeine_cancel_timer()
        result = _caffeine_apply(on, _caffeine_backend())
        if result == "ok" and on and minutes:
            _caffeine_schedule(time.time() + minutes * 60)
        return result


def caffeine_restore(state_dir: Path) -> None:
    """Al arrancar el daemon: retoma el temporizador guardado o, si venció
    mientras el daemon estaba abajo, apaga caffeine."""
    global _CAFFEINE_STATE
    _CAFFEINE_STATE = state_dir / "caffeine.json"
    try:
        until = float(json.loads(_CAFFEINE_STATE.read_text())["until"])
    except (OSError, ValueError, KeyError, TypeError):
        return
    with _CAFFEINE_LOCK:
        if until > time.time():
            _caffeine_schedule(until)
        else:
            _caffeine_save(None)
            _caffeine_apply(False, _caffeine_backend())


def caffeine_state() -> dict:
    backend = _caffeine_backend()
    with _CAFFEINE_LOCK:
        active = _caffeine_active(backend)
        if not active and _CAFFEINE_UNTIL is not None:
            _caffeine_cancel_timer()  # lo apagaron desde el PC
        until = _CAFFEINE_UNTIL
    return {
        "active": active,
        "backend": backend,
        "until": int(until) if until else None,
        "remaining_s": max(0, int(until - time.time())) if until else None,
    }
