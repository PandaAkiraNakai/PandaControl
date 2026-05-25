# Panda Control — Android

App Kotlin + Jetpack Compose que se conecta al backend `apppanda-backend`
(en `~/appPanda/backend/`) para monitorear y controlar la torre desde el
celular.

## Stack

- Kotlin 2.0.21 + Compose Compiler oficial
- Material 3 (tema cyberpunk: `#0d0d11`, `#ffea00`, `#ff007a`, `#00e5ff`)
- Navigation Compose
- Ktor 3.0.2 (HTTP + SSE manual sobre `bodyAsChannel`)
- DataStore Preferences (persiste host + token)
- minSdk 26, target/compile 35

## Estructura

```
app/src/main/java/io/github/pandaakira/apppanda/
├── MainActivity.kt
├── PandaApp.kt              # Application + repository singleton
├── data/
│   ├── Settings.kt          # DataStore: baseUrl + token
│   ├── PandaApi.kt          # Ktor client + parser SSE
│   ├── PandaRepository.kt   # api Flow + events Flow con reconexión
│   └── models/Models.kt     # data classes serializables
└── ui/
    ├── theme/{Color,Theme,Type}.kt
    ├── components/Common.kt # PandaCard, StatBar, KeyValue, EmptyState
    ├── nav/AppNav.kt        # NavGraph + bottom bar
    ├── onboarding/          # form host/puerto/token + test
    ├── home/                # live cards + lista eventos SSE
    ├── status/              # tabs Sistema/Disco/Red/Temps/GPU
    ├── trends/              # selector range + line chart (Canvas)
    └── input/               # módulo Control: touchpad mouse + teclado
```

## Build

Requisitos: JDK 17+ (recomendado 21), Android SDK 35.

```bash
cd ~/appPanda/android
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

`gradle.properties` ya apunta `org.gradle.java.home` a
`/usr/lib/jvm/java-21-openjdk`. Si tu JDK 21 está en otro path, edítalo.

## Instalar en el celular

Con el celular conectado por USB (depuración habilitada):

```bash
~/Android/Sdk/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

O ponlo en Telegram/Drive y bájalo al celular.

## Primer arranque

1. Abre Panda Control en el celular.
2. Ve a la pestaña **Setup** (icono engranaje).
3. Ingresa:
   - **Host**: `100.64.0.5` (la IP Tailscale de tu torre — solo
     funciona si tienes Tailscale corriendo en el celular y la torre
     está bindeada a esa IP, no a `127.0.0.1`).
   - **Puerto**: `8890`
   - **Bearer token**: el que generaste cuando instalaste el backend
     (está en `/etc/apppanda-backend/config.toml`, campo `tokens`).
4. Tap **Probar conexión** — debe contestar `OK · sergiotorre · 16 cores
   · 30 GB RAM`.
5. Tap **Guardar y entrar**.

## Pantallas (Fase 2)

| Tab | Qué muestra |
|---|---|
| Home | Cards CPU/RAM/GPU/Temp en vivo desde SSE + últimos eventos (boot/service_failed/session_new) |
| Status | Tabs Sistema · Disco · Red · Temps · GPU (pull, snapshot al entrar a cada tab) |
| Trends | Selector 1h/6h/24h + line chart Canvas (CPU/RAM/GPU/Disk en %, CPU/GPU en °C) |
| Control | Touchpad de mouse (mover, clic, scroll, swipe de dos dedos atrás/adelante) + teclado (teclas especiales, atajos y texto libre). Se entra desde Control. |
| Setup | Form de configuración del backend |

## Cómo cambiar a Tailscale en la torre

El backend bindea a `127.0.0.1` por default — la app desde el celular no
lo alcanza. Para abrirlo:

```bash
sudo -u $USER $EDITOR /etc/apppanda-backend/config.toml
# cambia: host = "100.64.0.5"   (IP Tailscale de tu torre)
sudo systemctl restart apppanda-backend
```

## Próximas fases

- **Fase 3** — acciones destructivas (poder, kill, services, app launch,
  wake-on-lan) con polkit narrow-scope en el backend + confirmación modal
  en la app.
- **Fase 4** — el resto (audio, pantallas, media, red detallado, vps,
  juegos, notas Obsidian).
