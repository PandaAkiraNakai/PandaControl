#!/usr/bin/env python3
"""
sudo-app-askpass — askpass helper que solicita aprobación a la app Panda
Control vía un popup full-screen, con fallback al bot de Telegram.

Flujo:
  1. sudo invoca este binary con SUDO_ASKPASS.
  2. Hacemos POST al backend → registra request + emite SSE 'sudo_request'.
  3. La app recibe el evento, dispara notif urgente con full-screen-intent,
     usuario aprueba/rechaza.
  4. Mientras tanto, este binary hace long-poll a /wait con timeout 60s.
  5. Si aprobado: imprimir password de /etc/apppanda-backend/sudo-password.
     Si rechazado: exit 1.
     Si timeout/error de red: exec fallback_askpass (sudo-telegram-askpass).

Config leído de /etc/apppanda-backend/config.toml:
  [sudo_app]
  enabled = true
  internal_token = "..."          # bearer interno del askpass binary
  password_file = "..."            # path al file con el password
  fallback_askpass = "..."         # binary fallback
  approval_timeout_s = 60

Backend URL fijo: http://127.0.0.1:8890 (loopback solo).
"""

import os
import sys
import json
import time
import urllib.request
import urllib.error
import urllib.parse

try:
    import tomllib
except ImportError:
    sys.stderr.write("python3.11+ required (tomllib missing)\n")
    sys.exit(2)


CONFIG_PATH = "/etc/apppanda-backend/config.toml"


def backend_base(cfg: dict) -> str:
    http = cfg.get("http", {}) or {}
    host = http.get("host") or "127.0.0.1"
    port = int(http.get("port") or 8890)
    # 0.0.0.0 no es ruteable como destino — usar loopback.
    if host == "0.0.0.0":
        host = "127.0.0.1"
    return f"http://{host}:{port}"


def load_cfg() -> dict:
    try:
        with open(CONFIG_PATH, "rb") as f:
            return tomllib.load(f)
    except (FileNotFoundError, PermissionError, tomllib.TOMLDecodeError) as e:
        sys.stderr.write(f"sudo-app-askpass: cannot read config: {e}\n")
        sys.exit(2)


def fallback(fallback_bin: str) -> "typing.NoReturn":
    """Reemplaza este proceso con el binary fallback (askpass viejo)."""
    if fallback_bin and os.path.isfile(fallback_bin) and os.access(fallback_bin, os.X_OK):
        sys.stderr.write(f"sudo-app-askpass: falling back to {fallback_bin}\n")
        os.execv(fallback_bin, [fallback_bin] + sys.argv[1:])
    sys.stderr.write(f"sudo-app-askpass: fallback {fallback_bin!r} not usable\n")
    sys.exit(1)


def main() -> None:
    cfg = load_cfg()
    sudo_cfg = cfg.get("sudo_app", {}) or {}
    if not sudo_cfg.get("enabled"):
        # Si está deshabilitado, ir directo al fallback.
        fallback(sudo_cfg.get("fallback_askpass", ""))

    internal_token = sudo_cfg.get("internal_token", "")
    if not internal_token:
        sys.stderr.write("sudo-app-askpass: internal_token vacío\n")
        fallback(sudo_cfg.get("fallback_askpass", ""))

    pw_file = sudo_cfg.get("password_file", "")
    if not pw_file or not os.path.isfile(pw_file):
        sys.stderr.write(f"sudo-app-askpass: password_file inexistente: {pw_file}\n")
        fallback(sudo_cfg.get("fallback_askpass", ""))

    timeout_s = int(sudo_cfg.get("approval_timeout_s", 60))
    fallback_bin = sudo_cfg.get("fallback_askpass", "")

    prompt = sys.argv[1] if len(sys.argv) > 1 else ""
    command = os.environ.get("SUDO_COMMAND", "")
    base = backend_base(cfg)

    # 1) Registrar request
    try:
        rid = post_request(base, prompt, command, internal_token)
    except Exception as e:
        sys.stderr.write(f"sudo-app-askpass: request failed: {e}\n")
        fallback(fallback_bin)

    # 2) Long-poll wait
    try:
        status = wait_decision(base, rid, timeout_s, internal_token)
    except Exception as e:
        sys.stderr.write(f"sudo-app-askpass: wait failed: {e}\n")
        fallback(fallback_bin)

    if status == "approved":
        # 3) Leer password y enviarlo a stdout (sudo lo consume)
        try:
            with open(pw_file, "r", encoding="utf-8") as f:
                pw = f.read().rstrip("\n")
            sys.stdout.write(pw + "\n")
            sys.stdout.flush()
            sys.exit(0)
        except OSError as e:
            sys.stderr.write(f"sudo-app-askpass: cannot read password: {e}\n")
            sys.exit(1)
    elif status == "denied":
        sys.stderr.write("sudo-app-askpass: denegado desde la app\n")
        sys.exit(1)
    elif status == "expired":
        sys.stderr.write("sudo-app-askpass: timeout sin respuesta — fallback\n")
        fallback(fallback_bin)
    else:
        sys.stderr.write(f"sudo-app-askpass: estado inesperado {status!r}\n")
        fallback(fallback_bin)


def post_request(base: str, prompt: str, command: str, token: str) -> str:
    data = json.dumps({"prompt": prompt, "command": command}).encode("utf-8")
    req = urllib.request.Request(
        f"{base}/api/v1/sudo/request",
        data=data, method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Internal-Token": token,
        },
    )
    with urllib.request.urlopen(req, timeout=5) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    rid = body.get("rid")
    if not rid:
        raise RuntimeError("no rid in response")
    return rid


def wait_decision(base: str, rid: str, timeout_s: int, token: str) -> str:
    url = f"{base}/api/v1/sudo/{rid}/wait?timeout={timeout_s}"
    req = urllib.request.Request(
        url, method="GET",
        headers={"X-Internal-Token": token},
    )
    with urllib.request.urlopen(req, timeout=timeout_s + 5) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return body.get("status", "expired")


if __name__ == "__main__":
    main()
