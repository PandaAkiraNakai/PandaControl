"""
sudo_broker — request-response queue para aprobar sudo desde la app.

Flujo:
  1. /usr/local/bin/sudo-app-askpass es invocado por sudo (vía SUDO_ASKPASS).
     Hace POST /api/v1/sudo/request con el prompt.
  2. El broker registra el request en memoria, le asigna un request_id (uuid)
     y emite un evento SSE 'sudo_request' a TODOS los suscriptores SSE.
  3. La app Android recibe el SSE → ForegroundService dispara una notif
     full-screen-intent que abre SudoApprovalActivity.
  4. El usuario aprueba o rechaza desde la app → POST /api/v1/sudo/{rid}/decision.
  5. Mientras tanto, el askpass binary hace long-poll a /api/v1/sudo/{rid}/wait
     que devuelve cuando hay decisión o timeout.
  6. Si aprobado: askpass imprime el password de /etc/apppanda-backend/sudo-password.
     Si rechazado: askpass exit 1.
     Si timeout / error: askpass exec /usr/local/bin/sudo-telegram-askpass
     (fallback al bot Telegram viejo).
"""

import threading
import time
import uuid


class SudoBroker:
    """In-memory queue de pending sudo requests. Thread-safe."""

    def __init__(self):
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        # rid -> {prompt, command, status, decided_at, requested_at}
        # status: "pending" | "approved" | "denied" | "expired"
        self._pending: dict[str, dict] = {}

    def new_request(self, prompt: str = "", command: str = "") -> str:
        rid = uuid.uuid4().hex[:16]
        now = time.time()
        with self._cond:
            self._pending[rid] = {
                "rid": rid,
                "prompt": prompt[:200],
                "command": command[:500],
                "status": "pending",
                "requested_at": now,
                "decided_at": 0.0,
            }
        return rid

    def get(self, rid: str) -> dict | None:
        with self._lock:
            entry = self._pending.get(rid)
            return dict(entry) if entry else None

    def decide(self, rid: str, approved: bool) -> bool:
        """Devuelve True si la decisión fue aplicada, False si rid inexistente
        o ya decidido."""
        with self._cond:
            entry = self._pending.get(rid)
            if entry is None or entry["status"] != "pending":
                return False
            entry["status"] = "approved" if approved else "denied"
            entry["decided_at"] = time.time()
            self._cond.notify_all()
            return True

    def wait_for_decision(self, rid: str, timeout_s: float) -> dict | None:
        """Long-poll: bloquea hasta que el request tenga decisión o expire el
        timeout. Devuelve el entry final o None si timeout."""
        deadline = time.monotonic() + timeout_s
        with self._cond:
            while True:
                entry = self._pending.get(rid)
                if entry is None:
                    return None
                if entry["status"] != "pending":
                    return dict(entry)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    entry["status"] = "expired"
                    entry["decided_at"] = time.time()
                    return dict(entry)
                self._cond.wait(timeout=min(remaining, 5.0))

    def cleanup_expired(self, max_age_s: float = 300) -> int:
        """Remueve requests viejos (más de max_age_s sin tocar)."""
        now = time.time()
        with self._lock:
            stale = [
                rid for rid, e in self._pending.items()
                if (now - e["requested_at"]) > max_age_s
            ]
            for rid in stale:
                del self._pending[rid]
            return len(stale)

    def pending_list(self) -> list[dict]:
        with self._lock:
            return [
                dict(e) for e in self._pending.values()
                if e["status"] == "pending"
            ]
