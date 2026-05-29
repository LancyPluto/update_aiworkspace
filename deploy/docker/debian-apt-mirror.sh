#!/bin/sh
# Replace Debian official apt sources with a domestic mirror (default: Aliyun).
set -eu

MIRROR="${APT_MIRROR:-mirrors.aliyun.com}"

replace_in_file() {
  file="$1"
  [ -f "$file" ] || return 0
  sed -i \
    -e "s@http://deb.debian.org@http://${MIRROR}@g" \
    -e "s@https://deb.debian.org@https://${MIRROR}@g" \
    -e "s@deb.debian.org@${MIRROR}@g" \
    -e "s@http://security.debian.org@http://${MIRROR}@g" \
    -e "s@https://security.debian.org@https://${MIRROR}@g" \
    -e "s@security.debian.org@${MIRROR}@g" \
    "$file"
}

replace_in_file /etc/apt/sources.list.d/debian.sources
replace_in_file /etc/apt/sources.list

echo "apt mirror -> ${MIRROR}"
