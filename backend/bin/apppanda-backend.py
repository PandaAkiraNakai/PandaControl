#!/usr/bin/env python3
"""
apppanda-backend — HTTP + SSE server con métricas de la torre.

Diseño:
  · Monitor thread cada `monitor.interval_s` toma snapshot (CPU/RAM/disk/
    temps/GPU/load) y lo guarda en SQLite (WAL). Publica `metric_tick` al
    EventBroker para que los clientes SSE conectados lo reciban en vivo.
  · Detecta servicios que pasan a failed, nuevas sesiones, retorno de
    suspend y boot fresco — los publica como eventos SSE.
  · HTTP server stdlib (http.server.ThreadingHTTPServer) en un thread
    aparte; responde JSON a /api/v1/*. Auth Bearer token.
  · NO tiene integración con Telegram. NO tiene polkit (Fase 1: read-only).
    Acciones destructivas (poder, kill, services) vienen en Fase 3.

Es hermano independiente de `bot-comandos-torre`: si uno cae, el otro
sigue funcionando. Ambos pueden coexistir y muestrean métricas del mismo
sistema en paralelo (cada uno con su propio SQLite).

Stdlib solamente. Opcional: matplotlib (PNG de tendencias — todavía no
expuesto en HTTP; los clientes Android dibujan con bibliotecas nativas).
"""

import os
import sys
import threading
import time
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

from panda_core import (
    Audit,
    CONFIG_PATH,
    load_config,
    run,
)
from panda_metrics import (
    Alerts,
    Calendar,
    History,
    Metrics,
    list_failed_services,
    list_sessions,
    top_processes,
)
from panda_media import (
    audio_app_inputs,
    audio_default_sink,
    audio_master,
    audio_set_app_mute,
    audio_set_app_volume,
    audio_set_mic_mute,
    audio_set_mic_volume,
    audio_set_mute,
    audio_set_volume,
    audio_sinks,
    audio_source,
    clipboard_get,
    clipboard_set,
    mpris_players,
    mpris_status,
)
from panda_screens import (
    active_compositor,
    audio_set_sink,
    mpris_action,
    player_video_fullscreen,
    scene_apply,
    scenes_list,
    screen_dpms,
    screen_outputs,
    screen_set_output,
    steam_close,
    steam_launch,
    steam_running,
    wm_cmd,
    wm_cmds,
    wol_info,
    wol_send,
)
from panda_system import (
    app_icon_png,
    default_gateway,
    docker_action,
    docker_list,
    docker_logs,
    execute_app,
    execute_apply_updates,
    execute_kill,
    execute_power,
    execute_svc,
    lan_neighbors,
    memorias_list,
    pedidos_list,
    session_state,
    steam_games,
    terminal_run,
    vps_status,
)
from panda_power import (
    caffeine_restore,
    caffeine_set,
    caffeine_state,
    power_inhibit,
    power_inhibit_state,
)


# ─── Context ─────────────────────────────────────────────────────────────────

class Context:
    def __init__(self, cfg, metrics, history, audit, broker,
                 alerts=None, sudo=None, calendar=None):
        self.cfg = cfg
        self.metrics = metrics
        self.history = history
        self.audit = audit
        self.broker = broker
        self.alerts = alerts
        self.sudo = sudo
        self.calendar = calendar


_ALERT_TITLES = {
    "cpu_pct":        ("⚠️ CPU alta",        "{v:.0f} %"),
    "ram_pct":        ("⚠️ RAM alta",        "{v:.0f} %"),
    "disk_pct":       ("⚠️ Disco lleno",     "{v:.0f} %"),
    "cpu_temp_c":     ("🔥 CPU caliente",    "{v:.0f} °C"),
    "gpu_temp_c":     ("🔥 GPU caliente",    "{v:.0f} °C"),
    "gpu_pct":        ("⚠️ GPU saturada",    "{v:.0f} %"),
    "load1_per_core": ("⚠️ Load alta",       "{v:.2f}/core"),
}


def _alert_label(key: str, value: float, disk_mount: str = "") -> tuple[str, str]:
    title, vfmt = _ALERT_TITLES.get(key, (key, "{v}"))
    val_str = vfmt.format(v=value)
    if key == "disk_pct" and disk_mount:
        val_str += f" ({disk_mount})"
    return title, val_str


# ─── Monitor loop ────────────────────────────────────────────────────────────

def monitor_loop(ctx: Context) -> None:
    cfg = ctx.cfg
    interval = int(cfg["monitor"]["interval_s"])
    state = {
        "failed_services": set(),
        "failed_services_seen": False,
        "sessions": set(),
        "sessions_seen": False,
        "last_tick": time.monotonic(),
    }

    ctx.metrics.cpu_pct()
    ctx.metrics.net_throughput()
    time.sleep(2)

    last_prune = 0.0
    while True:
        t0 = time.monotonic()
        wall_skip = t0 - state["last_tick"] - interval
        if wall_skip > 60 and state["last_tick"] != 0:
            ctx.broker.publish("resume", {"gap_s": wall_skip})
            ctx.audit.log("resume", gap_s=wall_skip)
        state["last_tick"] = t0

        try:
            cpu = ctx.metrics.cpu_pct()
            mem = ctx.metrics.memory()
            disk_mount, disk_pct = ctx.metrics.disk_max_pct()
            cpu_temp = ctx.metrics.cpu_temp_max()
            gpu_temp = ctx.metrics.gpu_max_temp()
            gpu_pct = ctx.metrics.gpu_max_pct()
            la = ctx.metrics.loadavg()

            cores = ctx.metrics.cpu_count()
            load_per_core = la[0] / cores if cores else 0.0

            snap = {
                "cpu_pct": cpu, "ram_pct": mem["pct"],
                "gpu_pct": gpu_pct, "cpu_temp": cpu_temp,
                "gpu_temp": gpu_temp, "load1": la[0],
                "disk_pct": disk_pct, "disk_mount": disk_mount,
            }
            ctx.history.insert(int(time.time()), snap)
            ctx.broker.publish("metric_tick", snap)

            # Evaluar alertas con histéresis
            if ctx.alerts is not None:
                for key, val in (
                    ("cpu_pct", cpu), ("ram_pct", mem["pct"]),
                    ("disk_pct", disk_pct), ("cpu_temp_c", cpu_temp),
                    ("gpu_temp_c", gpu_temp), ("gpu_pct", gpu_pct),
                    ("load1_per_core", load_per_core),
                ):
                    if ctx.alerts.evaluate(key, val):
                        title, val_str = _alert_label(key, val, disk_mount)
                        ctx.broker.publish("alert", {
                            "key": key, "value": val,
                            "title": title, "value_str": val_str,
                            "snap": snap,
                        })
                        ctx.audit.log("alert", key=key, value=val)

            failed_now = set(list_failed_services())
            new_failed = failed_now - state["failed_services"]
            if new_failed and state["failed_services_seen"]:
                for svc in sorted(new_failed):
                    ctx.broker.publish("service_failed", {"unit": svc})
                    ctx.audit.log("service_failed", service=svc)
            state["failed_services"] = failed_now
            state["failed_services_seen"] = True

            sessions_now = list_sessions()
            new_sess = sessions_now - state["sessions"]
            if new_sess and state["sessions_seen"]:
                for s in sorted(new_sess):
                    ctx.broker.publish("session_new", {"session": s})
                    ctx.audit.log("session_new", session=s)
            state["sessions"] = sessions_now
            state["sessions_seen"] = True

            # Recordatorios de calendario: avisar de los eventos que tocan.
            if ctx.calendar is not None:
                for ev in ctx.calendar.pop_due_reminders(datetime.now()):
                    cuando = ev["inicio"][11:16] if "T" in ev["inicio"] else "todo el día"
                    ctx.broker.publish("calendario_recordatorio", {
                        "title": ev["titulo"],
                        "value_str": cuando,
                    })
                    ctx.audit.log("calendario_recordatorio", evento=ev["id"])

            now = time.time()
            if now - last_prune > 6 * 3600:
                ctx.history.prune()
                last_prune = now
        except Exception as e:
            print(f"monitor: tick failed: {e!r}", file=sys.stderr)

        elapsed = time.monotonic() - t0
        time.sleep(max(1.0, interval - elapsed))


# ─── Tick rápido (push de métricas vivas a SSE) ──────────────────────────────

def fast_tick_loop(ctx: Context) -> None:
    """Publica un metric_tick más liviano cada `monitor.tick_publish_s` para
    feed de la app en vivo, sin tocar el SQLite. CPU% se mide entre lecturas.
    """
    interval = max(1, int(ctx.cfg["monitor"].get("tick_publish_s", 5)))
    ctx.metrics.cpu_pct()
    time.sleep(1)
    while True:
        try:
            cpu = ctx.metrics.cpu_pct()
            mem = ctx.metrics.memory()
            la = ctx.metrics.loadavg()
            gpu_pct = ctx.metrics.gpu_max_pct()
            gpu_temp = ctx.metrics.gpu_max_temp()
            cpu_temp = ctx.metrics.cpu_temp_max()
            ctx.broker.publish("metric_tick", {
                "cpu_pct": cpu, "ram_pct": mem["pct"],
                "gpu_pct": gpu_pct, "cpu_temp": cpu_temp,
                "gpu_temp": gpu_temp, "load1": la[0],
                "live": True,
            })
        except Exception as e:
            print(f"fast_tick: failed: {e!r}", file=sys.stderr)
        time.sleep(interval)


# ─── Boot notification ───────────────────────────────────────────────────────

def maybe_publish_boot(cfg: dict, audit: Audit, broker) -> None:
    try:
        with open("/proc/sys/kernel/random/boot_id") as f:
            boot_id = f.read().strip()
    except OSError:
        return
    state_dir = Path(cfg["history"]["db_path"]).parent
    boot_marker = state_dir / "last_boot_id"
    last_boot_id = ""
    if boot_marker.exists():
        try:
            last_boot_id = boot_marker.read_text().strip()
        except OSError:
            pass
    if not boot_id or boot_id == last_boot_id:
        return
    try:
        with open("/proc/uptime") as f:
            up = float(f.read().split()[0])
    except OSError:
        up = 0.0
    hostname = run(["hostname"]).strip() or "?"
    broker.publish("boot", {
        "boot_id": boot_id, "uptime_s": up, "hostname": hostname,
    })
    audit.log("boot", uptime_s=up, boot_id=boot_id)
    try:
        boot_marker.write_text(boot_id + "\n")
    except OSError as e:
        print(f"could not persist boot marker: {e}", file=sys.stderr)


# ─── main ────────────────────────────────────────────────────────────────────

def main() -> None:
    cfg = load_config(CONFIG_PATH)
    metrics = Metrics()
    history = History(cfg["history"]["db_path"], cfg["history"]["retain_days"])
    audit = Audit(cfg["audit"]["log_path"])
    calendar = (Calendar(cfg["calendario"]["db_path"])
                if cfg["calendario"].get("enabled", True) else None)
    # Retoma un temporizador de caffeine que quedó corriendo antes de reiniciar.
    caffeine_restore(Path(cfg["history"]["db_path"]).parent)

    from http_server import EventBroker, start_http_server
    from sudo_broker import SudoBroker
    import input_control
    broker = EventBroker()
    alerts = Alerts(cfg)
    sudo = SudoBroker()

    ctx = Context(cfg, metrics, history, audit, broker,
                  alerts=alerts, sudo=sudo, calendar=calendar)

    audit.log("start", pid=os.getpid(), config=CONFIG_PATH)
    print(
        f"apppanda-backend starting (monitor={cfg['monitor']['enabled']}, "
        f"http={cfg['http']['enabled']})",
        file=sys.stderr,
    )

    if cfg["http"]["enabled"]:
        api = SimpleNamespace(
            ctx=ctx,
            run=run,
            top_processes=top_processes,
            list_failed_services=list_failed_services,
            list_sessions=list_sessions,
            niri_outputs=screen_outputs,
            niri_set_output=screen_set_output,
            niri_dpms=screen_dpms,
            active_compositor=active_compositor,
            niri_cmd=wm_cmd,
            wm_cmds=wm_cmds,
            audio_sinks=audio_sinks,
            audio_default_sink=audio_default_sink,
            audio_set_sink=audio_set_sink,
            audio_master=audio_master,
            audio_set_volume=audio_set_volume,
            audio_set_mute=audio_set_mute,
            audio_app_inputs=audio_app_inputs,
            audio_set_app_volume=audio_set_app_volume,
            audio_set_app_mute=audio_set_app_mute,
            audio_source=audio_source,
            audio_set_mic_mute=audio_set_mic_mute,
            audio_set_mic_volume=audio_set_mic_volume,
            clipboard_get=clipboard_get,
            clipboard_set=clipboard_set,
            scenes_list=scenes_list,
            scene_apply=scene_apply,
            terminal_run=terminal_run,
            memorias_list=memorias_list,
            pedidos_list=pedidos_list,
            docker_list=docker_list,
            docker_logs=docker_logs,
            docker_action=docker_action,
            steam_running=steam_running,
            steam_close=steam_close,
            power_inhibit=power_inhibit,
            power_inhibit_state=power_inhibit_state,
            caffeine_set=caffeine_set,
            caffeine_state=caffeine_state,
            app_icon_png=app_icon_png,
            mpris_players=mpris_players,
            mpris_status=mpris_status,
            mpris_action=mpris_action,
            player_video_fullscreen=player_video_fullscreen,
            lan_neighbors=lan_neighbors,
            default_gateway=default_gateway,
            vps_status=vps_status,
            steam_games=steam_games,
            steam_launch=steam_launch,
            wol_send=wol_send,
            wol_info=wol_info,
            execute_power=execute_power,
            session_state=session_state,
            execute_kill=execute_kill,
            execute_svc=execute_svc,
            execute_app=execute_app,
            execute_apply_updates=execute_apply_updates,
            input_mouse_move=input_control.mouse_move,
            input_mouse_click=input_control.mouse_click,
            input_mouse_scroll=input_control.mouse_scroll,
            input_cursor_highlight=input_control.cursor_highlight,
            input_key_press=input_control.key_press,
            input_type_text=input_control.type_text,
        )
        start_http_server(
            api, broker,
            host=cfg["http"]["host"],
            port=int(cfg["http"]["port"]),
            tokens=list(cfg["http"]["tokens"]),
            audit=audit,
            tailscale_auth=cfg["auth"]["tailscale"],
        )

    maybe_publish_boot(cfg, audit, broker)

    if cfg["monitor"]["enabled"]:
        threading.Thread(
            target=monitor_loop, args=(ctx,), daemon=True, name="monitor",
        ).start()
        threading.Thread(
            target=fast_tick_loop, args=(ctx,), daemon=True, name="fast-tick",
        ).start()

    # Loop main thread (idle, daemon threads hacen el trabajo)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
