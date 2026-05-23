# apppanda-backend

Daemon Python (stdlib) que expone HTTP REST + SSE para que la app Android
Panda lea métricas y eventos de la torre. Service systemd, corre como
`sergioc`, independiente del bot de Telegram.

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
| `GET /api/v1/audio/sinks` | pactl, sinks + default |
| `GET /api/v1/screens` | niri outputs |
| `GET /api/v1/media/players` + `/{player}/status` | playerctl/MPRIS |
| `GET /api/v1/net/neighbors` | ip neigh + gateway |
| `GET /api/v1/vps` + `/{alias}/summary` | SSH BatchMode |
| `GET /api/v1/games` | biblioteca Steam |
| `GET /api/v1/apps` | config de [apps.*] |
| `GET /api/v1/updates` | checkupdates parsed |
| `GET /api/v1/browser/tabs` | pestañas abiertas de Brave (CDP) |
| `GET /api/v1/browser/links?target=ID` | elementos clicables de la página + etiqueta |
| `GET /api/v1/web/search?q=...` | búsqueda web (DuckDuckGo HTML) |
| `GET /api/v1/youtube/search?q=...` | búsqueda YouTube (ytInitialData) |
| `GET /api/v1/events` | SSE: `hello`, `metric_tick`, `service_failed`, `session_new`, `boot`, `resume` |

## Módulo Navegador (Brave vía CDP)

`browser.py` controla Brave por Chrome DevTools Protocol con un cliente
WebSocket mínimo de stdlib (sin librerías externas). Requiere lanzar Brave
con `--remote-debugging-port=9222 --remote-allow-origins=*`, escuchando solo
en `127.0.0.1` (cómodo vía `~/.config/brave-flags.conf`). Las búsquedas web
y de YouTube se parsean por HTTP, sin abrir el navegador.

Acciones (POST, body JSON):

| Path | Body | Qué hace |
|---|---|---|
| `/api/v1/browser/open` | `{url}` | abre una URL o búsqueda en una pestaña nueva |
| `/api/v1/browser/navigate` | `{target, url}` | navega la pestaña `target` |
| `/api/v1/browser/{activate\|close\|reload\|back\|forward}` | `{target}` | control de pestaña |
| `/api/v1/browser/scroll` | `{target, dir}` | scroll (`up\|down\|top\|bottom`) con rueda CDP |
| `/api/v1/browser/click` | `{target, text}` | clic en el enlace/botón visible que contenga `text` |
| `/api/v1/browser/click_index` | `{target, idx}` | clic en el elemento `idx` listado por `/browser/links` |
| `/api/v1/browser/type` | `{target, text, submit}` | escribe en el campo de texto principal |
| `/api/v1/youtube/play` | `{videoId, target?}` | reproduce un video en Brave |

## Inventario (después de instalar)

| Path | Dueño | Perms | Qué es |
|---|---|---|---|
| `/usr/local/bin/apppanda-backend` | root:root | 0755 | El daemon |
| `/usr/local/bin/http_server_panda.py` | root:root | 0644 | Módulo HTTP (symlink: `http_server.py`) |
| `/etc/apppanda-backend/config.toml` | sergioc:sergioc | 0400 | Tokens + bind + monitor + opcional vps/apps/etc |
| `/etc/systemd/system/apppanda-backend.service` | root:root | 0644 | Unit |
| `/var/lib/apppanda-backend/metrics.db` | sergioc:sergioc | 0600 | SQLite WAL del histórico |
| `/var/log/apppanda-backend/audit.log` | sergioc:sergioc | 0640 + `chattr +a` | Audit JSONL append-only |

## Instalar

```bash
cd ~/appPanda/backend
sudo bash INSTALL.sh
```

Editar el config con un token y arrancar:

```bash
sudo -u sergioc $EDITOR /etc/apppanda-backend/config.toml
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
