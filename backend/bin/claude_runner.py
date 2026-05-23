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

import json
import os
import signal
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Callable


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
        self._current_turn: str | None = None
        self._thread: threading.Thread | None = None

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
        """Drop session_id (nueva conversación). Si hay un turno en curso,
        lo cancela."""
        self._cancel_locked()
        with self._lock:
            self._session_id = None
        self._persist_state()
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
        cmd.append(prompt)
        return cmd

    def _spawn(self, cmd: list[str]) -> subprocess.Popen:
        env = os.environ.copy()
        env.setdefault("TERM", "dumb")
        # Asegurar que ~/.local/bin esté en PATH (claude CLI vive ahí en
        # instalaciones por usuario). systemd suele dar un PATH minimal.
        extra = os.path.expanduser("~/.local/bin")
        cur_path = env.get("PATH", "")
        if extra not in cur_path.split(":"):
            env["PATH"] = f"{extra}:{cur_path}" if cur_path else extra
        cwd = os.path.expanduser(
            str(self.cfg.get("working_dir", "~")),
        )
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
        return proc

    def _cancel_locked(self) -> bool:
        with self._lock:
            proc = self._proc
        if proc and proc.poll() is None:
            try:
                proc.send_signal(signal.SIGINT)
            except OSError:
                return False
            return True
        return False

    def _run_turn(self, turn_id: str, prompt: str) -> None:
        started_at = time.monotonic()
        new_session_id: str | None = None
        ok = False
        error_msg: str | None = None
        proc: subprocess.Popen | None = None
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
                    # algunos formatos vuelven 'result' al final con metadata
                    pass
            rc = proc.wait()
            stderr_tail = ""
            if proc.stderr is not None:
                try:
                    stderr_tail = proc.stderr.read()[-500:]
                except Exception:
                    pass
            if rc == 0:
                ok = True
            else:
                error_msg = stderr_tail.strip() or f"claude exit {rc}"
        except Exception as e:
            error_msg = f"runner: {e}"
        finally:
            with self._lock:
                self._proc = None
                self._current_turn = None
            if new_session_id:
                self._session_id = new_session_id
                self._persist_state()
            duration_s = time.monotonic() - started_at
            self._publish("ai_done", {
                "turn_id": turn_id,
                "ok": ok,
                "duration_s": round(duration_s, 2),
                "session_id": self._session_id,
                "error": error_msg,
            })
            self._publish("ai_state", self.state())

    def _handle_assistant(self, turn_id: str, evt: dict) -> None:
        msg = evt.get("message") or {}
        blocks = msg.get("content") or []
        for blk in blocks:
            btype = blk.get("type")
            if btype == "text":
                txt = blk.get("text", "")
                if txt:
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
