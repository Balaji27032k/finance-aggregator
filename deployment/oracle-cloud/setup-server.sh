#!/usr/bin/env bash
# One-time server setup for Ubuntu 22.04/24.04 on Oracle Cloud. Run as: sudo bash setup-server.sh <your-domain>
set -euo pipefail

DOMAIN="${1:?Usage: sudo bash setup-server.sh <domain, e.g. mybackend.duckdns.org>}"
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "==> Installing Java 21, Caddy and tools"
apt-get update -y
apt-get install -y openjdk-21-jre-headless debian-keyring debian-archive-keyring apt-transport-https curl gpg iptables-persistent
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi

echo "==> Creating service user and folders"
id finance >/dev/null 2>&1 || useradd --system --home /opt/finance-aggregator --shell /usr/sbin/nologin finance
mkdir -p /opt/finance-aggregator /etc/finance-aggregator/secrets
chown -R root:finance /etc/finance-aggregator
chmod 750 /etc/finance-aggregator /etc/finance-aggregator/secrets

echo "==> Opening ports 80 and 443 in the host firewall (Oracle images block them by default)"
iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT
iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport 443 -j ACCEPT
netfilter-persistent save

echo "==> Installing Caddy config for ${DOMAIN}"
sed "s/__DOMAIN__/${DOMAIN}/" "${HERE}/Caddyfile.template" > /etc/caddy/Caddyfile
systemctl enable --now caddy
systemctl reload caddy

echo "==> Installing the backend service"
install -m 644 "${HERE}/finance-backend.service" /etc/systemd/system/finance-backend.service
systemctl daemon-reload
systemctl enable finance-backend

echo
echo "Setup done. Next: copy app.jar, env and aiven-ca.p12 (see docs/CLOUD_DEPLOYMENT.md, step 9), then run: sudo systemctl restart finance-backend"
