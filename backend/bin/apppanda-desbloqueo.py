#!/usr/bin/env python3
"""
apppanda-desbloqueo — inicia la sesión gráfica desde la pantalla de login.

Lo arranca apppanda-desbloqueo.service (como root) cuando la app pide
"Desbloquear" y el PC está en la pantalla de inicio de sesión de Plasma Login
(recién encendido o reiniciado). Se conecta al socket del greeter del daemon
plasmalogin y le manda el mismo mensaje Login que manda la pantalla de login
al escribir la contraseña, así PAM abre KWallet y el llavero igual que en un
inicio de sesión a mano (un autologin los dejaría cerrados).

La contraseña sale del mismo archivo que usa el sudo desde la app
([sudo_app].password_file). Nunca se reintenta: cada rechazo suma a faillock.

Protocolo (plasma-login-manager 6.7: src/common/Messages.h, Session.h y
src/daemon/SocketServer.cpp), QDataStream big-endian:
  greeter -> daemon   quint32 1 (Login), QString usuario, QString contraseña,
                      quint32 tipo de sesión (0 X11, 1 Wayland), QString archivo
  daemon -> greeter   quint32 0 (LoginSucceeded) | 1 (LoginFailed) |
                      2 (InformationMessage) + QString

Códigos de salida (el backend los traduce para la app):
   0 login aceptado              10 ya hay una sesión gráfica activa
  11 no hay pantalla de login    12 contraseña rechazada
  13 plasmalogin no respondió    14 falta el archivo de contraseña
  15 configuración inválida

`--probar` manda un Login de un usuario inexistente sin mirar si hay sesión:
con una sesión abierta plasmalogin lo descarta sin autenticar, pero deja en
su journal "start auth user true <comando>", que confirma que el mensaje se
decodificó bien. No toca faillock del usuario real.
"""

import os
import pwd
import socket
import stat
import struct
import subprocess
import sys
import time
import tomllib
from pathlib import Path

CONFIG_PATH = "/etc/apppanda-backend/config.toml"
DEFAULT_PASSWORD_FILE = "/etc/apppanda-backend/sudo-password"

GREETER_LOGIN = 1
LOGIN_SUCCEEDED, LOGIN_FAILED, INFORMATION_MESSAGE = 0, 1, 2
SESSION_TYPES = {"x11": 0, "wayland": 1}

REPLY_TIMEOUT_S = 20


def qstring(text: str) -> bytes:
    data = text.encode("utf-16-be")
    return struct.pack(">I", len(data)) + data


def login_message(user: str, password: str, session_type: int, session: str) -> bytes:
    return (struct.pack(">I", GREETER_LOGIN) + qstring(user) + qstring(password)
            + struct.pack(">I", session_type) + qstring(session))


def active_graphical_session(user: str) -> str | None:
    out = subprocess.run(["loginctl", "list-sessions", "--no-legend", "--no-pager"],
                         capture_output=True, text=True, timeout=10).stdout
    for line in out.splitlines():
        parts = line.split()
        if not parts:
            continue
        props = subprocess.run(
            ["loginctl", "show-session", parts[0], "-p", "Name", "-p", "Type",
             "-p", "Class", "-p", "State", "-p", "Active"],
            capture_output=True, text=True, timeout=10,
        ).stdout
        p = dict(l.split("=", 1) for l in props.splitlines() if "=" in l)
        if (p.get("Name") == user and p.get("Class") == "user"
                and p.get("Type") in SESSION_TYPES and p.get("State") != "closing"
                and p.get("Active") == "yes"):
            return parts[0]
    return None


def greeter_sockets() -> list[Path]:
    """Sockets del greeter: /tmp/plasmalogin-<display>-<azar>, del usuario
    plasmalogin. Los /tmp/plasmalogin-auth-* son del helper de PAM."""
    try:
        uid = pwd.getpwnam("plasmalogin").pw_uid
    except KeyError:
        return []
    found = []
    for path in Path("/tmp").glob("plasmalogin-*"):
        if path.name.startswith("plasmalogin-auth-"):
            continue
        try:
            st = path.lstat()
        except OSError:
            continue
        if stat.S_ISSOCK(st.st_mode) and st.st_uid == uid:
            found.append((st.st_mtime, path))
    return [path for _, path in sorted(found, reverse=True)]


def read_reply(sock: socket.socket, timeout_s: float) -> int | None:
    buf = b""
    deadline = time.monotonic() + timeout_s
    while True:
        left = deadline - time.monotonic()
        if left <= 0:
            return None
        sock.settimeout(left)
        try:
            chunk = sock.recv(4096)
        except TimeoutError:
            return None
        if not chunk:
            return None
        buf += chunk
        while len(buf) >= 4:
            (msg,) = struct.unpack(">I", buf[:4])
            if msg in (LOGIN_SUCCEEDED, LOGIN_FAILED):
                return msg
            if msg != INFORMATION_MESSAGE:
                print(f"mensaje desconocido de plasmalogin: {msg}")
                return None
            if len(buf) < 8:
                break
            (size,) = struct.unpack(">I", buf[4:8])
            size = 0 if size == 0xFFFFFFFF else size
            if len(buf) < 8 + size:
                break
            print("plasmalogin:", buf[8:8 + size].decode("utf-16-be", "replace"))
            buf = buf[8 + size:]


def main() -> int:
    probe = "--probar" in sys.argv[1:]
    user = os.environ.get("APPPANDA_USER", "")
    try:
        with open(CONFIG_PATH, "rb") as f:
            cfg = tomllib.load(f)
    except (OSError, tomllib.TOMLDecodeError) as e:
        print(f"no se pudo leer {CONFIG_PATH}: {e}")
        cfg = {}
    unlock_cfg = cfg.get("desbloqueo") or {}
    session = str(unlock_cfg.get("sesion", "plasma"))
    session_type = SESSION_TYPES.get(str(unlock_cfg.get("tipo", "wayland")))
    if not user or session_type is None:
        print("falta APPPANDA_USER o [desbloqueo].tipo no es x11/wayland")
        return 15

    if probe:
        user, password = "apppanda-prueba", "prueba"
    else:
        if active_graphical_session(user):
            print(f"{user} ya tiene una sesión gráfica activa")
            return 10
        password_file = (cfg.get("sudo_app") or {}).get("password_file",
                                                        DEFAULT_PASSWORD_FILE)
        try:
            password = Path(password_file).read_text().rstrip("\n")
        except OSError as e:
            print(f"no se pudo leer la contraseña ({password_file}): {e}")
            return 14
        if not password:
            print(f"{password_file} está vacío")
            return 14

    candidates = greeter_sockets()
    if not candidates:
        print("no hay socket del greeter de plasmalogin en /tmp")
        return 11
    for path in candidates:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            try:
                sock.connect(str(path))
            except OSError as e:
                print(f"{path}: {e}")
                continue
            print(f"login de {user} ({session}, {'wayland' if session_type else 'x11'}) por {path}")
            sock.sendall(login_message(user, password, session_type, session))
            reply = read_reply(sock, 5 if probe else REPLY_TIMEOUT_S)
        if reply == LOGIN_SUCCEEDED:
            print("login aceptado")
            return 0
        if reply == LOGIN_FAILED:
            print("plasmalogin rechazó el login")
            return 12
        print("plasmalogin no respondió")
        return 13
    return 11


if __name__ == "__main__":
    sys.exit(main())
