#!/bin/bash
# INSTALL.sh — instala apppanda-backend en este host.
#
# Correr como root desde este directorio:
#     sudo bash INSTALL.sh
#
# Idempotente. No pisa /etc/apppanda-backend/config.toml si ya existe.

set -euo pipefail

if [ "$(id -u)" != "0" ]; then
    echo "INSTALL.sh tiene que correr como root" >&2
    exit 1
fi

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_USER="${SUDO_USER:-sergioc}"
TARGET_GROUP="$(id -gn "$TARGET_USER")"

echo "==> Source: $SRC_DIR"
echo "==> Target user: $TARGET_USER:$TARGET_GROUP"

# 1. Config dir
echo "==> Creating /etc/apppanda-backend"
install -d -m 0750 -o "$TARGET_USER" -g "$TARGET_GROUP" /etc/apppanda-backend

# 2. Daemon binary
echo "==> Installing /usr/local/bin/apppanda-backend"
install -m 0755 -o root -g root \
    "$SRC_DIR/bin/apppanda-backend.py" \
    /usr/local/bin/apppanda-backend

# 2b. http_server module (imported por el daemon)
echo "==> Installing /usr/local/bin/http_server_panda.py"
install -m 0644 -o root -g root \
    "$SRC_DIR/bin/http_server.py" \
    /usr/local/bin/http_server_panda.py

# Compat: el daemon hace `from http_server import ...`, y Python agrega
# dirname(sys.argv[0]) al sys.path. Para que apppanda-backend encuentre
# SU módulo (y no choque con un eventual http_server.py de otro daemon),
# lo instalamos como http_server_panda.py y dejamos un alias symlink
# http_server.py local al binario.
ln -sfn /usr/local/bin/http_server_panda.py /usr/local/bin/http_server.py

# 2c. sudo_broker module (imported por el daemon cuando [sudo_app] enabled)
echo "==> Installing /usr/local/bin/sudo_broker.py"
install -m 0644 -o root -g root \
    "$SRC_DIR/bin/sudo_broker.py" \
    /usr/local/bin/sudo_broker.py

# 2e. claude_runner module (imported por el daemon para módulo IA)
echo "==> Installing /usr/local/bin/claude_runner.py"
install -m 0644 -o root -g root \
    "$SRC_DIR/bin/claude_runner.py" \
    /usr/local/bin/claude_runner.py

# 2f. browser module (imported por el daemon para control de Brave vía CDP)
echo "==> Installing /usr/local/bin/browser.py"
install -m 0644 -o root -g root \
    "$SRC_DIR/bin/browser.py" \
    /usr/local/bin/browser.py

# 2d. sudo-app-askpass binary (reemplazo de SUDO_ASKPASS)
echo "==> Installing /usr/local/bin/sudo-app-askpass"
install -m 0755 -o root -g root \
    "$SRC_DIR/bin/sudo-app-askpass.py" \
    /usr/local/bin/sudo-app-askpass

# 3. systemd unit (con sustitución de __TARGET_USER__ / __TARGET_GROUP__)
echo "==> Installing /etc/systemd/system/apppanda-backend.service"
sed -e "s|__TARGET_USER__|$TARGET_USER|g" \
    -e "s|__TARGET_GROUP__|$TARGET_GROUP|g" \
    "$SRC_DIR/config/apppanda-backend.service" \
    > /etc/systemd/system/apppanda-backend.service
chown root:root /etc/systemd/system/apppanda-backend.service
chmod 0644 /etc/systemd/system/apppanda-backend.service

# 3b. polkit rule narrow-scope (Fase 3: poder + manage-units)
echo "==> Installing /etc/polkit-1/rules.d/50-apppanda-backend.rules"
sed -e "s|__TARGET_USER__|$TARGET_USER|g" \
    "$SRC_DIR/config/50-apppanda-backend.rules" \
    > /etc/polkit-1/rules.d/50-apppanda-backend.rules
chown root:root /etc/polkit-1/rules.d/50-apppanda-backend.rules
chmod 0644 /etc/polkit-1/rules.d/50-apppanda-backend.rules

# 4. State dir (SQLite)
echo "==> Creating /var/lib/apppanda-backend"
install -d -m 0700 -o "$TARGET_USER" -g "$TARGET_GROUP" /var/lib/apppanda-backend

# 5. Log dir + audit.log con append-only
echo "==> Creating /var/log/apppanda-backend"
install -d -m 0750 -o "$TARGET_USER" -g "$TARGET_GROUP" /var/log/apppanda-backend
AUDIT="/var/log/apppanda-backend/audit.log"
if [ ! -f "$AUDIT" ]; then
    touch "$AUDIT"
    chown "$TARGET_USER:$TARGET_GROUP" "$AUDIT"
    chmod 0640 "$AUDIT"
fi
if command -v chattr >/dev/null 2>&1; then
    chattr +a "$AUDIT" 2>/dev/null || \
        echo "    (warn: chattr +a en audit.log no aplicó — sigue siendo writable)"
fi

# 6. Config (solo si falta)
if [ ! -f /etc/apppanda-backend/config.toml ]; then
    echo "==> Installing /etc/apppanda-backend/config.toml (EDIT BEFORE STARTING)"
    install -m 0400 -o "$TARGET_USER" -g "$TARGET_GROUP" \
        "$SRC_DIR/config/config.example.toml" \
        /etc/apppanda-backend/config.toml
else
    echo "==> /etc/apppanda-backend/config.toml ya existe (no se modifica)"
    echo "    (revisa config.example.toml si hay settings nuevos)"
fi

# 7. Reload systemd + polkit
echo "==> systemctl daemon-reload"
systemctl daemon-reload

if systemctl is-active --quiet polkit 2>/dev/null; then
    systemctl reload polkit 2>/dev/null || systemctl restart polkit 2>/dev/null || true
fi

cat <<POST

──────────────────────────────────────────────────────────────────────
apppanda-backend instalado.

Próximos pasos:

  1. Edita /etc/apppanda-backend/config.toml — agrega tokens y opcionalmente
     cambia host a la IP Tailscale (100.x.y.z) cuando quieras acceso
     desde el celular:
       sudo -u $TARGET_USER \$EDITOR /etc/apppanda-backend/config.toml

  2. Arranca:
       sudo systemctl start apppanda-backend
       sudo journalctl -fu apppanda-backend

  3. Health check:
       curl http://127.0.0.1:8890/api/v1/health

  4. Si todo OK:
       sudo systemctl enable apppanda-backend
──────────────────────────────────────────────────────────────────────
POST
