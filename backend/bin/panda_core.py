"""Config, helpers comunes y audit log del backend de PandaControl.

Separado de apppanda-backend.py; ver ese archivo para el diseño general.
"""

import json
import os
import subprocess
import sys
import threading
from datetime import datetime, timezone

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
    # Temas visuales de la app: cada *.json en este dir define una paleta.
    # La app los lista vía /api/v1/themes y los aplica al vuelo. Agregar un
    # archivo nuevo basta — no hace falta reiniciar el daemon ni recompilar.
    cfg.setdefault("themes", {})
    cfg["themes"].setdefault("dir", "~/.config/apppanda-backend/temas")
    cfg["steam"].setdefault("exclude_appids", [
        "228980", "1070560", "1391110", "1493710", "1628350", "4183110",
    ])
    # Escenas: presets que combinan outputs on/off + foco + sink de audio.
    # Cada [scenes.<name>] = {label, outputs_on, outputs_off, focus, sink}.
    cfg.setdefault("scenes", {})
    # Mini terminal: opt-in. Ejecuta bash -lc como el usuario (sin sudo).
    cfg.setdefault("terminal", {})
    cfg["terminal"].setdefault("enabled", False)
    cfg["terminal"].setdefault("timeout_s", 20)
    cfg["terminal"].setdefault("max_output_kb", 64)
    # Memorias y pedidos: visor de la base del CLI `mem` (ambas tablas viven
    # en la misma SQLite). Solo lectura, sin Ollama ni embeddings.
    cfg.setdefault("memorias", {})
    cfg["memorias"].setdefault("enabled", True)
    cfg["memorias"].setdefault("db_path", "~/.local/share/mem/memory.db")
    # Docker: lista y gestiona contenedores vía el CLI `docker`. El usuario del
    # daemon tiene que estar en el grupo `docker` (o tener acceso al socket).
    cfg.setdefault("docker", {})
    cfg["docker"].setdefault("enabled", True)
    # Calendario self-hosted: eventos en una SQLite propia del daemon (CRUD
    # completo). Los recordatorios se publican por SSE como cualquier alerta.
    cfg.setdefault("calendario", {})
    cfg["calendario"].setdefault("enabled", True)
    cfg["calendario"].setdefault(
        "db_path", "/var/lib/apppanda-backend/calendario.db")
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
