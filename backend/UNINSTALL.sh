#!/bin/bash
# UNINSTALL.sh — desinstala apppanda-backend del sistema.
#
# Correr como root:
#     sudo bash UNINSTALL.sh
#
# DEJA INTACTO:
#   - /etc/apppanda-backend/  (config con tokens)
#   - /var/lib/apppanda-backend/  (SQLite histórico)
#   - /var/log/apppanda-backend/  (audit.log con chattr +a)
#
# Borrá manualmente si querés limpiar todo:
#   sudo chattr -a /var/log/apppanda-backend/audit.log
#   sudo rm -rf /etc/apppanda-backend /var/lib/apppanda-backend /var/log/apppanda-backend

set -euo pipefail

if [ "$(id -u)" != "0" ]; then
    echo "UNINSTALL.sh tiene que correr como root" >&2
    exit 1
fi

echo "==> Stopping + disabling apppanda-backend"
systemctl stop apppanda-backend 2>/dev/null || true
systemctl disable apppanda-backend 2>/dev/null || true

echo "==> Removing /etc/systemd/system/apppanda-backend.service"
rm -f /etc/systemd/system/apppanda-backend.service

echo "==> Removing polkit rule"
rm -f /etc/polkit-1/rules.d/50-apppanda-backend.rules

echo "==> Removing /usr/local/bin/apppanda-backend"
rm -f /usr/local/bin/apppanda-backend

echo "==> Removing /usr/local/bin/http_server_panda.py + symlink"
rm -f /usr/local/bin/http_server.py /usr/local/bin/http_server_panda.py

systemctl daemon-reload

echo "==> done."
echo "    Config + SQLite + audit log quedaron intactos (ver script)."
