# Panda Control

> Panel Android para controlar y monitorear tu PC Linux desde el celular,
> a través de tu propia tailnet de Tailscale.

```
┌────────────┐         Tailscale         ┌──────────────────┐
│  Android   │ ◄────  WireGuard tunnel ─►│   torre Linux    │
│  Panda Control  │      HTTP REST + SSE      │ apppanda-backend │
└────────────┘                           └──────────────────┘
```

App Kotlin/Compose con tema cyberpunk + daemon Python stdlib que expone
HTTP REST + SSE sobre tu tailnet privada. Sin servicios externos, sin
push de terceros, sin telemetría — el celu habla directo con tu PC.

## Qué hace

- **Status** — CPU, RAM, disco, red, temperaturas, GPUs, SMART
- **Trends** — gráficas históricas 1h/6h/24h (Canvas Compose nativo)
- **Media** — control MPRIS (play/pause/seek±15/fullscreen), cambiar sink
  de audio, on/off pantallas + DPMS, lanzar apps GUI, lanzar juegos Steam
- **Modules** — poder (off/reboot/suspend/lock con confirm), procesos +
  kill, servicios con start/stop/restart, logs `journalctl`, updates
  `checkupdates` con apply, vecinos LAN, VPS via SSH, ajustes
- **Push del sistema** — ForegroundService que mantiene SSE en background
  y dispara notificaciones nativas para alertas con histéresis (CPU/RAM/
  disk/temps/GPU/load), servicios `failed`, sesiones nuevas, boot, resume
  de suspend

## Auth

Dos esquemas, ambos opcionales:

- **Identidad Tailscale**: el backend llama `tailscale whois --json <peer_ip>`
  y autoriza si `UserProfile.LoginName` está en `allowed_logins`. La app
  no necesita token — solo estar en tu tailnet con tu cuenta.
- **Bearer token**: header `Authorization: Bearer <hex>`. Útil fuera de
  Tailscale o para automatización.

## Estructura

```
appPanda/
├── backend/                       Daemon Python (HTTP + SSE)
│   ├── bin/{apppanda-backend,http_server}.py
│   ├── config/{apppanda-backend.service, config.example.toml,
│   │           50-apppanda-backend.rules}
│   ├── INSTALL.sh · UNINSTALL.sh
│   └── README.md
└── android/                       App Kotlin + Jetpack Compose
    ├── app/                       (Material 3, Ktor, DataStore, Compose)
    ├── build.gradle.kts · settings.gradle.kts
    └── README.md
```

## Backend — instalar

Requisitos: Python 3.11+, systemd, `tailscale` instalado y conectado a la
tailnet. Linux con `pactl` + `niri` + `playerctl` + `wtype` para las
funciones de Media (opcional — el daemon arranca igual sin ellas).

```bash
cd backend
sudo bash INSTALL.sh

# Generar token (opcional si vas con Tailscale auth)
python -c "import secrets; print(secrets.token_hex(32))"

# Editar config: host (IP Tailscale o 127.0.0.1), tokens, allowed_logins
sudo -u $USER $EDITOR /etc/apppanda-backend/config.toml

sudo systemctl enable --now apppanda-backend
curl http://127.0.0.1:8890/api/v1/health
```

Detalle completo en [`backend/README.md`](backend/README.md).

## App — build

Requisitos: JDK 17+, Android SDK 35.

```bash
cd android
./gradlew assembleDebug
# APK debug en app/build/outputs/apk/debug/app-debug.apk
```

Para release firmado, crear `android/keystore.properties`:

```properties
storeFile=/ruta/absoluta/a/tu-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Generá el keystore con `keytool -genkey -v -keystore tu-keystore.jks
-keyalg RSA -keysize 4096 -validity 36500 -alias appanda`.

Luego: `./gradlew assembleRelease`.

Detalle completo en [`android/README.md`](android/README.md).

## Endpoints

```
GET  /api/v1/health · /version
GET  /api/v1/status/{system,disk,net,temps,gpu,smart}
GET  /api/v1/processes?sort=cpu|ram&limit=N
GET  /api/v1/services
GET  /api/v1/logs?priority=err&n=30
GET  /api/v1/metrics?range=1h|6h|24h
GET  /api/v1/audio/sinks
GET  /api/v1/screens
GET  /api/v1/media/players  ·  /media/{player}/status
GET  /api/v1/net/neighbors
GET  /api/v1/vps  ·  /vps/{alias}/summary
GET  /api/v1/games  ·  /apps  ·  /updates
GET  /api/v1/events   (Server-Sent Events: metric_tick / alert /
                       service_failed / session_new / boot / resume)

POST /api/v1/power/{off|reboot|suspend|lock}        X-Confirm: true
POST /api/v1/processes/{pid}/kill                   X-Confirm: true
POST /api/v1/services/{unit}/{start|stop|restart}   X-Confirm: true
POST /api/v1/updates/apply                          X-Confirm: true
POST /api/v1/audio/sink            {sink: "..."}
POST /api/v1/screens/{output}/{on|off}
POST /api/v1/screens/dpms/{on|off}
POST /api/v1/media/{player}/{play-pause|next|previous|seek:+15|seek:-15|fullscreen|vol-up|vol-down}
POST /api/v1/apps/{name}/launch
POST /api/v1/games/{appid}/launch
POST /api/v1/net/wake/{alias}
```

## Stack

- **Backend**: Python 3.11+ stdlib only (`http.server`, `sqlite3`,
  `threading`, `subprocess`). Opcionales: `lm_sensors`, `pacman-contrib`,
  `smartmontools`, `pactl`, `niri`, `playerctl`, `wtype`.
- **App**: Kotlin 2.0.21, Jetpack Compose, Material 3, Navigation Compose,
  Ktor 3 (CIO + content-negotiation + SSE manual), DataStore Preferences,
  kotlinx.serialization, minSdk 26, target 35.

## Filosofía

- Solo stdlib en el backend — un único archivo de daemon + un módulo HTTP.
- Cero servicios externos. Cero telemetría. Tailscale es la única
  dependencia de red.
- Auth nativo a la identidad de Tailscale: si tu device está en tu
  tailnet con tu cuenta, pasa.
- Polkit narrow-scope para las acciones destructivas: el daemon NO corre
  como root.
- Audit log JSONL append-only (`chattr +a`) de toda acción.

## License

MIT. Ver [LICENSE](LICENSE).
