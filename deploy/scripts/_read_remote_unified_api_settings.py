#!/usr/bin/env python3
from __future__ import annotations

import os
import sys

import paramiko


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD", "")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    sys.stdout.reconfigure(encoding="utf-8")

    path = "/root/ai_tool_market/admin-frontend/components/admin/unified-api-settings.tsx"
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)

    cmd = f"""python3 - <<'PY'
import itertools, io
p = {path!r}
f = io.open(p, 'r', encoding='utf-8', errors='replace')
lines = list(itertools.islice(f, 94, 112))
print('--- remote unified-api-settings.tsx [95..112] ---')
for idx, ln in enumerate(lines, start=95):
    print(f"{{idx}}|{{ln.rstrip()}}")
PY"""
    _, out, err = ssh.exec_command(cmd, timeout=60)
    data = (out.read() or b"") + (err.read() or b"")
    print(data.decode("utf-8", errors="replace"))
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

