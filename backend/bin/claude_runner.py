"""
claude_runner — corre `claude -p` para el módulo IA de la app.

Replica el comportamiento del bot claude-telegram viejo: una query a la
vez, parsea events JSON line-by-line, captura session_id para que /new
permita resumir conversaciones tras reinicios, y publica chunks de texto
+ tool uses como eventos SSE para que la app los renderice en vivo.

Eventos SSE publicados al broker:
- ai_chunk   {turn_id, delta}            texto incremental del assistant
- ai_tool    {turn_id, tool, summary}    inicio de tool use (render inline)
- ai_done    {turn_id, ok, duration_s,   fin del turno (success o error)
              session_id, error?}
- ai_state   {busy, session_id, model}   cambios de estado (reset, model)
"""

from __future__ import annotations

import datetime
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Callable


# Cuántos turnos (user+assistant pairs) re-inyectar de contexto cuando se
# pierde la sesión. Heurística: ~20 turnos suelen ser un par de minutos de
# chat y entran cómodos en el contexto de claude sin disparar el costo.
CHAT_LOG_REINJECT_TURNS = 20


TOOL_ICONS = {
    "Read": "📖",
    "Edit": "✏️",
    "Write": "✏️",
    "Bash": "⚡",
    "Grep": "🔎",
    "Glob": "🔎",
    "WebFetch": "🌐",
    "WebSearch": "🌐",
    "Task": "🤖",
    "TaskCreate": "🤖",
    "TaskUpdate": "🤖",
    "TaskList": "🤖",
}


def _load_state(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return {}


def _save_state(path: Path, state: dict) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(state, indent=2))
        tmp.replace(path)
    except OSError as e:
        print(f"claude_runner: no pude persistir state: {e}", file=sys.stderr)


class ClaudeRunner:
    """Corre queries de `claude -p` serialmente. Una en curso a la vez.

    El consumidor llama .start(prompt) — devuelve un turn_id inmediato si
    la query arrancó, o None si ya hay una en curso. El parsing del stream
    y la publicación de eventos SSE corren en un thread aparte.
    """

    def __init__(
        self,
        cfg: dict,
        publish: Callable[[str, dict], None],
        state_path: str | Path,
    ):
        self.cfg = cfg
        self._publish = publish
        self._state_path = Path(state_path)

        st = _load_state(self._state_path)
        self._session_id: str | None = st.get("session_id")
        self._model: str | None = st.get("model")

        self._lock = threading.Lock()
        self._proc: subprocess.Popen | None = None
        # Nombre del unit transient del gestor de systemd del usuario bajo el
        # que corre claude (ver _spawn). None si se lanzó directo.
        self._user_unit: str | None = None
        self._current_turn: str | None = None
        self._thread: threading.Thread | None = None

        # Buffer del texto del assistant acumulado durante el turno actual,
        # para appendearlo al chat-log cuando termine ok.
        self._assistant_buf: list[str] = []

    # ─── estado público ──────────────────────────────────────────────────

    @property
    def session_id(self) -> str | None:
        return self._session_id

    @property
    def model(self) -> str | None:
        return self._model

    @property
    def busy(self) -> bool:
        with self._lock:
            return self._proc is not None and self._proc.poll() is None

    def state(self) -> dict:
        return {
            "busy": self.busy,
            "session_id": self._session_id,
            "model": self._model or "default",
            "working_dir": str(self.cfg.get("working_dir", os.path.expanduser("~"))),
            "turn_id": self._current_turn,
        }

    # ─── acciones ────────────────────────────────────────────────────────

    def set_model(self, model: str | None) -> dict:
        valid = {"opus", "sonnet", "haiku", "default", None}
        if model is not None and not isinstance(model, str):
            return {"result": "error", "error": "model debe ser string"}
        if model in ("", "default"):
            model = None
        # Permitir IDs completos también (e.g. claude-opus-4-7), no solo aliases.
        with self._lock:
            self._model = model
        self._persist_state()
        self._publish("ai_state", self.state())
        return {"result": "ok", "model": model or "default"}

    def reset(self) -> dict:
        """Drop session_id + truncate chat-log (nueva conversación de cero).
        Si hay un turno en curso, lo cancela."""
        self._cancel_locked()
        with self._lock:
            self._session_id = None
        self._persist_state()
        self._log_truncate()
        self._publish("ai_state", self.state())
        return {"result": "ok"}

    def cancel(self) -> dict:
        ok = self._cancel_locked()
        return {"result": "ok" if ok else "noop"}

    def start(self, prompt: str) -> dict:
        if not prompt or not prompt.strip():
            return {"result": "error", "error": "prompt vacío"}
        if not self.cfg.get("enabled", True):
            return {"result": "error", "error": "ai disabled en config"}
        with self._lock:
            if self._proc is not None and self._proc.poll() is None:
                return {"result": "error", "error": "ya hay un turno en curso",
                        "turn_id": self._current_turn}
            turn_id = uuid.uuid4().hex[:12]
            self._current_turn = turn_id
        # Lanzar parser en thread separado.
        self._thread = threading.Thread(
            target=self._run_turn, args=(turn_id, prompt), daemon=True,
        )
        self._thread.start()
        self._publish("ai_state", self.state())
        return {"result": "ok", "turn_id": turn_id}

    # ─── internals ───────────────────────────────────────────────────────

    def _persist_state(self) -> None:
        _save_state(self._state_path, {
            "session_id": self._session_id,
            "model": self._model,
        })

    def _build_cmd(self, prompt: str) -> list[str]:
        cmd = [self.cfg.get("claude_bin", "claude"), "-p"]
        cmd.extend(["--output-format", "stream-json", "--verbose"])
        if self._session_id:
            cmd.extend(["-r", self._session_id])
        if self._model:
            cmd.extend(["--model", self._model])
        cmd.extend(list(self.cfg.get("extra_args", [])))
        # Si no tenemos session_id pero hay chat-log persistido (la sesión
        # se perdió o ai-state.json fue reseteado pero el log sobrevivió),
        # prependemos los últimos N turnos como contexto para que claude
        # no arranque sin memoria.
        actual_prompt = (
            self._build_context_prompt(prompt)
            if not self._session_id
            else prompt
        )
        cmd.append(actual_prompt)
        return cmd

    # ─── chat-log persistence ────────────────────────────────────────────

    def _log_path(self) -> Path:
        return self._state_path.parent / "chat-log.jsonl"

    def _log_append(self, role: str, text: str) -> None:
        text = (text or "").strip()
        if not text:
            return
        try:
            p = self._log_path()
            p.parent.mkdir(parents=True, exist_ok=True)
            entry = {
                "role": role,
                "text": text,
                "ts": datetime.datetime.now().isoformat(timespec="seconds"),
            }
            with open(p, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        except OSError as e:
            print(f"claude_runner: chat-log append falló: {e}", file=sys.stderr)

    def _log_truncate(self) -> None:
        try:
            p = self._log_path()
            if p.exists():
                p.unlink()
        except OSError as e:
            print(f"claude_runner: chat-log truncate falló: {e}", file=sys.stderr)

    def _log_read_recent(self, limit_turns: int) -> list[dict]:
        """Devuelve las últimas 2*limit_turns entradas del log (cada par
        user+assistant = un turno)."""
        try:
            p = self._log_path()
            if not p.exists():
                return []
            with open(p, encoding="utf-8") as f:
                lines = f.readlines()
        except OSError:
            return []
        recent = lines[-(limit_turns * 2):]
        out: list[dict] = []
        for line in recent:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return out

    def _build_context_prompt(self, current_prompt: str) -> str:
        """Reconstruye los últimos N turnos del chat-log como bloque de
        contexto prepended al prompt actual. Devuelve current_prompt sin
        tocar si no hay log."""
        recent = self._log_read_recent(CHAT_LOG_REINJECT_TURNS)
        if not recent:
            return current_prompt
        parts = [
            "[Contexto: la sesión anterior con este usuario se perdió, "
            "pero estos son los últimos mensajes intercambiados. Continuá "
            "la conversación desde acá.]",
            "",
        ]
        for entry in recent:
            role = entry.get("role", "?")
            text = (entry.get("text") or "").strip()
            if not text:
                continue
            if role == "user":
                parts.append(f"Usuario: {text}")
            elif role == "assistant":
                parts.append(f"Asistente: {text}")
            parts.append("")
        parts.append("[Fin del contexto. Mensaje actual del usuario:]")
        parts.append("")
        parts.append(current_prompt)
        return "\n".join(parts)

    def _spawn(self, cmd: list[str]) -> subprocess.Popen:
        env = os.environ.copy()
        env.setdefault("TERM", "dumb")
        # Asegurar que ~/.local/bin esté en PATH (claude CLI vive ahí en
        # instalaciones por usuario). systemd suele dar un PATH minimal.
        extra = os.path.expanduser("~/.local/bin")
        cur_path = env.get("PATH", "")
        if extra not in cur_path.split(":"):
            env["PATH"] = f"{extra}:{cur_path}" if cur_path else extra
        # SUDO_ASKPASS: el servicio systemd no hereda /etc/profile.d, así que
        # claude arrancaba sin él y cualquier `sudo` que pidiera el usuario por
        # el chat fallaba con "a terminal is required ... configure an askpass
        # helper". Con esto seteado y sin tty (somos un servicio), sudo invoca
        # el askpass AUTOMÁTICAMENTE — sin necesidad de `-A` — y la aprobación
        # va al overlay de la app. Ver memoria sudo-via-panda-control-app.
        env.setdefault(
            "SUDO_ASKPASS",
            str(self.cfg.get("sudo_askpass", "/usr/local/bin/sudo-app-askpass")),
        )
        cwd = os.path.expanduser(
            str(self.cfg.get("working_dir", "~")),
        )
        # El unit del backend corre con NoNewPrivileges=yes (hardening). Ese
        # flag se hereda a claude → su herramienta Bash → sudo e impide el
        # setuid de sudo: sudo aborta ANTES de invocar el askpass, así que el
        # overlay de aprobación de la app ni se dispara (esto es "el sandbox no
        # me deja" que reporta el chat). NoNewPrivileges no se puede limpiar y
        # además queda implícito por varias directivas Protect*/Restrict* del
        # unit, así que no se arregla relajando una sola línea.
        #
        # Solución: lanzar claude vía el gestor de systemd del USUARIO
        # (systemd-run --user). El proceso corre fuera del árbol hardened del
        # backend, con NoNewPrivs=0, mientras el backend sigue blindado — así
        # sudo puede escalar y la aprobación va a la app. Necesita
        # XDG_RUNTIME_DIR del usuario (el unit del backend no lo hereda). Si
        # systemd-run o ese runtime dir no están, caemos al spawn directo
        # (claude anda, pero sin poder hacer sudo).
        user_unit: str | None = None
        runtime_dir = env.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}"
        if (
            self.cfg.get("spawn_via_user_manager", True)
            and os.path.isdir(runtime_dir)
            and shutil.which("systemd-run")
        ):
            env["XDG_RUNTIME_DIR"] = runtime_dir
            user_unit = f"apppanda-ai-{uuid.uuid4().hex[:8]}"
            # Sólo reenviamos al unit lo que claude/sus tools necesitan; NO las
            # vars que systemd inyecta al backend (INVOCATION_ID, NOTIFY_SOCKET…).
            fwd_exact = {
                "HOME", "PATH", "TERM", "SUDO_ASKPASS", "XDG_RUNTIME_DIR",
                "LANG", "USER", "LOGNAME",
            }
            setenvs = [
                f"--setenv={k}={v}"
                for k, v in env.items()
                if v and (
                    k in fwd_exact
                    or k.startswith(("LC_", "CLAUDE_", "ANTHROPIC_"))
                )
            ]
            cmd = [
                "systemd-run", "--user", "--quiet", "--collect",
                "--pipe", "--wait", f"--unit={user_unit}",
                f"--working-directory={cwd}",
                *setenvs, "--", *cmd,
            ]

        proc = subprocess.Popen(
            cmd, cwd=cwd, env=env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,  # line-buffered para que --output-format stream-json
                        # entregue líneas tan pronto como claude las emita.
        )
        with self._lock:
            self._proc = proc
            self._user_unit = user_unit
        return proc

    def _cancel_locked(self) -> bool:
        with self._lock:
            proc = self._proc
            unit = self._user_unit
        if not (proc and proc.poll() is None):
            return False
        # Si claude corre bajo el gestor de systemd del usuario, matar el
        # proceso systemd-run NO detiene el unit (vive en otro árbol). Hay que
        # pararlo explícitamente: eso termina claude y systemd-run --wait
        # retorna. El SIGINT queda como fallback (y cubre el spawn directo).
        if unit:
            runtime_dir = (
                os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}"
            )
            try:
                subprocess.run(
                    ["systemctl", "--user", "stop", unit],
                    env={**os.environ, "XDG_RUNTIME_DIR": runtime_dir},
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=10, check=False,
                )
            except (OSError, subprocess.SubprocessError):
                pass
        try:
            proc.send_signal(signal.SIGINT)
        except OSError:
            return False
        return True

    def _run_turn(self, turn_id: str, prompt: str) -> None:
        started_at = time.monotonic()
        result: dict | None = None
        try:
            result = self._run_attempt(turn_id, prompt)

            # Auto-recovery: si la sesión guardada ya no existe en disco,
            # claude sale con "No conversation found with session ID: X".
            # Limpiamos el id muerto y reintentamos: el segundo attempt
            # spawn-eará sin -r y _build_cmd reinyectará el chat-log como
            # contexto, así claude continúa la conversación.
            if (
                not result["ok"]
                and result["session_not_found"]
                and self._session_id is not None
            ):
                self._session_id = None
                self._persist_state()
                result = self._run_attempt(turn_id, prompt)

            if result["new_session_id"]:
                self._session_id = result["new_session_id"]
                self._persist_state()

            # Loguear sólo turnos completos: si terminó ok, persistimos
            # el par (user, assistant) en el chat-log para reinyecciones
            # futuras. Si falló, el turno no entra al log — la próxima
            # reinyección no verá un user huérfano.
            if result["ok"]:
                assistant_text = "".join(self._assistant_buf).strip()
                if assistant_text:
                    self._log_append("user", prompt)
                    self._log_append("assistant", assistant_text)
        finally:
            with self._lock:
                self._proc = None
                self._user_unit = None
                self._current_turn = None
            self._assistant_buf = []
            duration_s = time.monotonic() - started_at
            self._publish("ai_done", {
                "turn_id": turn_id,
                "ok": result["ok"] if result else False,
                "duration_s": round(duration_s, 2),
                "session_id": self._session_id,
                "error": (
                    result["error_msg"] if result else "runner: turno abortado"
                ),
            })
            self._publish("ai_state", self.state())

    def _run_attempt(self, turn_id: str, prompt: str) -> dict:
        """Una invocación a `claude -p`. Devuelve un dict con:
        - ok: bool
        - new_session_id: str | None (si init emitió uno)
        - error_msg: str | None
        - session_not_found: bool (si stderr/result indicó 'No conversation
          found with session ID' — señal para auto-retry sin -r)
        """
        # Resetear buffer en cada attempt: si attempt 1 streameó texto
        # parcial y falló, no queremos contaminar lo que registramos en
        # el log con la respuesta de attempt 2.
        self._assistant_buf = []
        new_session_id: str | None = None
        ok = False
        error_msg: str | None = None
        session_not_found = False
        try:
            cmd = self._build_cmd(prompt)
            proc = self._spawn(cmd)
            assert proc.stdout is not None
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    evt = json.loads(line)
                except json.JSONDecodeError:
                    continue
                etype = evt.get("type")
                if etype == "system" and evt.get("subtype") == "init":
                    sid = evt.get("session_id")
                    if sid:
                        new_session_id = sid
                elif etype == "assistant":
                    self._handle_assistant(turn_id, evt)
                elif etype == "result":
                    if evt.get("subtype") == "error_during_execution":
                        errs = evt.get("errors") or []
                        for e in errs:
                            if isinstance(e, str) and "No conversation found" in e:
                                session_not_found = True
                                error_msg = e
                                break
            rc = proc.wait()
            stderr_tail = ""
            if proc.stderr is not None:
                try:
                    stderr_tail = proc.stderr.read()[-500:]
                except Exception:
                    pass
            if stderr_tail and "No conversation found" in stderr_tail:
                session_not_found = True
            if rc == 0 and not session_not_found:
                ok = True
            elif not error_msg:
                error_msg = stderr_tail.strip() or f"claude exit {rc}"
        except Exception as e:
            error_msg = f"runner: {e}"
        return {
            "ok": ok,
            "new_session_id": new_session_id,
            "error_msg": error_msg,
            "session_not_found": session_not_found,
        }

    def _handle_assistant(self, turn_id: str, evt: dict) -> None:
        msg = evt.get("message") or {}
        blocks = msg.get("content") or []
        for blk in blocks:
            btype = blk.get("type")
            if btype == "text":
                txt = blk.get("text", "")
                if txt:
                    self._assistant_buf.append(txt)
                    self._publish("ai_chunk", {
                        "turn_id": turn_id,
                        "delta": txt,
                    })
            elif btype == "tool_use":
                tool = blk.get("name", "tool")
                icon = TOOL_ICONS.get(tool, "🔧")
                summary = _tool_summary(tool, blk.get("input") or {})
                self._publish("ai_tool", {
                    "turn_id": turn_id,
                    "tool": tool,
                    "icon": icon,
                    "summary": summary,
                })


def _tool_summary(tool: str, params: dict) -> str:
    """Resumen corto para mostrar inline en el chat."""
    if tool in ("Read", "Edit", "Write"):
        p = params.get("file_path") or params.get("path") or ""
        return _shorten(p, 60)
    if tool == "Bash":
        return _shorten(params.get("command", ""), 80)
    if tool in ("Grep", "Glob"):
        return _shorten(params.get("pattern", ""), 60)
    if tool in ("WebFetch", "WebSearch"):
        return _shorten(params.get("url") or params.get("query") or "", 60)
    if tool.startswith("Task"):
        return _shorten(
            params.get("description") or params.get("subject") or "", 60,
        )
    return ""


def _shorten(s: str, n: int) -> str:
    s = str(s)
    return s if len(s) <= n else s[: n - 1] + "…"
