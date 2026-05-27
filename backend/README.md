# apppanda-backend

Daemon Python (stdlib) que expone HTTP REST + SSE para que la app Android
Panda lea métricas y eventos de la torre. Service systemd, corre como tu
usuario (no root).

## Endpoints (Fase 1: read-only)

Auth con `Authorization: Bearer <token>`. `/health` y `/version` son
públicos.

| Path | Datos |
|---|---|
| `GET /api/v1/health` | probe |
| `GET /api/v1/version` | version + hostname + boot_id |
| `GET /api/v1/status/system` | CPU/RAM/uptime/load |
| `GET /api/v1/status/disk` | df por mount |
| `GET /api/v1/status/net` | interfaces + ping a `[network].ping_hosts` |
| `GET /api/v1/status/temps` | lm_sensors o /sys/class/thermal |
| `GET /api/v1/status/gpu` | AMD + NVIDIA |
| `GET /api/v1/status/smart` | smartctl -H (si `[smart].devices`) |
| `GET /api/v1/processes?sort=cpu\|ram&limit=N` | top procesos |
| `GET /api/v1/services` | failed + watch + manageable |
| `GET /api/v1/logs?priority=err&n=30` | journalctl |
| `GET /api/v1/metrics?range=1h\|6h\|24h` | histórico SQLite |
| `GET /api/v1/audio/sinks` | pactl, sinks + default + `master` (volumen % / mute) |
| `GET /api/v1/clipboard` | texto del portapapeles (wl-paste) |
| `GET /api/v1/screens` | niri outputs |
| `GET /api/v1/media/players` + `/{player}/status` | playerctl/MPRIS |
| `GET /api/v1/net/neighbors` | ip neigh + gateway |
| `GET /api/v1/vps` + `/{alias}/summary` | SSH BatchMode |
| `GET /api/v1/games` | biblioteca Steam |
| `GET /api/v1/apps` | config de [apps.*] |
| `GET /api/v1/updates` | checkupdates parsed |
| `GET /api/v1/themes` | temas visuales (`*.json` de `[themes].dir`) |
| `GET /api/v1/themes/image?name=...` | imagen de fondo de un tema (archivo de la carpeta) |
| `GET /api/v1/events` | SSE: `hello`, `metric_tick`, `service_failed`, `session_new`, `boot`, `resume`, `sudo_request` |

## Módulo Control (mouse + teclado)

`input_control.py` inyecta input al compositor: el mouse vía el socket de
`ydotoold` (movimiento interpolado a alta frecuencia, clic izq/medio/der,
botones laterales atrás/adelante y scroll) y el teclado vía `wtype` (teclas
especiales, atajos y texto libre). Endpoints POST: `/api/v1/input/mouse/move`,
`/click`, `/scroll`, `/stream` (deltas en streaming) y `/api/v1/input/key`,
`/api/v1/input/type`.

El portapapeles del PC se sincroniza vía `wl-clipboard`:
`POST /api/v1/clipboard {text}` escribe (wl-copy) y `GET /api/v1/clipboard`
lee (wl-paste). El volumen maestro del sink por defecto se controla con
`POST /api/v1/audio/volume {pct: 0..150}` y
`POST /api/v1/audio/mute {state: on|off|toggle}` (vía `pactl`).

## Inventario (después de instalar)

| Path | Dueño | Perms | Qué es |
|---|---|---|---|
| `/usr/local/bin/apppanda-backend` | root:root | 0755 | El daemon |
| `/usr/local/bin/http_server_panda.py` | root:root | 0644 | Módulo HTTP (symlink: `http_server.py`) |
| `/etc/apppanda-backend/config.toml` | $USER:$USER | 0400 | Tokens + bind + monitor + opcional vps/apps/etc |
| `/etc/systemd/system/apppanda-backend.service` | root:root | 0644 | Unit |
| `/var/lib/apppanda-backend/metrics.db` | $USER:$USER | 0600 | SQLite WAL del histórico |
| `/var/log/apppanda-backend/audit.log` | $USER:$USER | 0640 + `chattr +a` | Audit JSONL append-only |

## Instalar

```bash
cd ~/appPanda/backend
sudo bash INSTALL.sh
```

Editar el config con un token y arrancar:

```bash
sudo -u $USER $EDITOR /etc/apppanda-backend/config.toml
sudo systemctl start apppanda-backend
sudo systemctl enable apppanda-backend
sudo journalctl -fu apppanda-backend
```

Generar token:

```bash
python -c "import secrets; print(secrets.token_hex(32))"
```

## Exponer al celular (Tailscale)

Por default bindea `127.0.0.1:8890` (no alcanzable desde fuera). Para que
la app Android acceda, cambiar en el config:

```toml
[http]
host = "100.64.0.5"   # IP Tailscale de esta torre
```

Y restart. Nunca `0.0.0.0` — incluso siendo read-only, expone procesos,
journal, métricas del sistema, etc.

## Operaciones

| Acción | Comando |
|---|---|
| Estado | `sudo systemctl status apppanda-backend` |
| Logs | `sudo journalctl -fu apppanda-backend` |
| Restart | `sudo systemctl restart apppanda-backend` |
| Health | `curl http://127.0.0.1:8890/api/v1/health` |
| Reinstall / upgrade | `sudo bash ~/appPanda/backend/INSTALL.sh` |
| Desinstalar | `sudo bash UNINSTALL.sh` |

## Relación con bot-comandos-torre

Son services completamente independientes. Coexisten muestreando métricas
en paralelo del mismo sistema, cada uno con su propio SQLite. Si uno cae,
el otro sigue. Bajar/levantar uno no afecta al otro.

Si quieres desactivar el bot de Telegram pero dejar la app:

```bash
sudo systemctl stop bot-comandos-torre
sudo systemctl disable bot-comandos-torre
```

(o al revés).
