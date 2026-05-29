#!/usr/bin/env bash
# Configure domestic mirrors on Ubuntu server (apt / docker / pip / npm).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_DIR="${DEPLOY_DIR}/config"

echo "==> Docker registry mirrors (China only)"
mkdir -p /etc/docker
cp "${CONFIG_DIR}/docker-daemon-china.json" /etc/docker/daemon.json
systemctl daemon-reload
systemctl restart docker

echo "==> APT mirrors (Aliyun Ubuntu)"
CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME}")"
cat > /etc/apt/sources.list <<EOF
deb http://mirrors.aliyun.com/ubuntu/ ${CODENAME} main restricted universe multiverse
deb http://mirrors.aliyun.com/ubuntu/ ${CODENAME}-updates main restricted universe multiverse
deb http://mirrors.aliyun.com/ubuntu/ ${CODENAME}-backports main restricted universe multiverse
deb http://mirrors.aliyun.com/ubuntu/ ${CODENAME}-security main restricted universe multiverse
EOF
if ls /etc/apt/sources.list.d/*docker* &>/dev/null; then
  sed -i 's|download.docker.com|mirrors.aliyun.com/docker-ce|g' /etc/apt/sources.list.d/*docker* 2>/dev/null || true
  sed -i 's|mirrors.cloud.aliyuncs.com/docker-ce|mirrors.aliyun.com/docker-ce|g' /etc/apt/sources.list.d/*docker* 2>/dev/null || true
fi
apt-get update -qq

echo "==> pip mirror (Aliyun)"
cat > /etc/pip.conf <<'EOF'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
trusted-host = mirrors.aliyun.com

[install]
trusted-host = mirrors.aliyun.com
EOF

echo "==> npm mirror (npmmirror)"
npm config set registry https://registry.npmmirror.com --global 2>/dev/null || true
cat > /root/.npmrc <<'EOF'
registry=https://registry.npmmirror.com
disturl=https://npmmirror.com/dist
sass_binary_site=https://npmmirror.com/mirrors/node-sass
electron_mirror=https://npmmirror.com/mirrors/electron/
puppeteer_download_host=https://npmmirror.com/mirrors
EOF

echo "==> Maven settings for Docker backend"
mkdir -p /root/.m2
cp "${CONFIG_DIR}/maven-settings-china.xml" /root/.m2/settings.xml

echo "Done. Docker mirrors:"
docker info 2>/dev/null | sed -n '/Registry Mirrors/,/^[^ ]/p' || true
