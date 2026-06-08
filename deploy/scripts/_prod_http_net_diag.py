#!/usr/bin/env python3
import os
import sys

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
USER = os.environ.get("DEPLOY_USER", "root")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")


def run(ssh: paramiko.SSHClient, title: str, cmd: str, timeout: int = 180) -> None:
    print(f"\n=== {title} ===")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out:
        print(out.rstrip())
    if err:
        print(err.rstrip(), file=sys.stderr)
    print(f"[exit={stdout.channel.recv_exit_status()}]")


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)

    run(ssh, "workspace", "cd /root/ai_tool_market && pwd && git rev-parse --abbrev-ref HEAD && git rev-parse --short HEAD")
    run(ssh, "containers", "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'")
    run(ssh, "compose ps", "cd /root/ai_tool_market && docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.nginx.yml ps")
    run(ssh, "nginx conf", "docker exec ai-supermarket-nginx sed -n '1,220p' /etc/nginx/conf.d/default.conf")
    run(
        ssh,
        "local http codes",
        "curl -s -o /tmp/wl-root.html -w 'root:%{http_code} %{redirect_url}\\n' --max-time 15 http://127.0.0.1/; "
        "curl -s -o /tmp/wl-admin.html -w 'admin:%{http_code} %{redirect_url}\\n' --max-time 15 http://127.0.0.1/admin/; "
        "curl -k -s -o /tmp/wl-root-https.html -w 'root_https:%{http_code}\\n' --max-time 15 https://127.0.0.1/; "
        "curl -k -s -o /tmp/wl-admin-https.html -w 'admin_https:%{http_code}\\n' --max-time 15 https://127.0.0.1/admin/",
    )
    run(
        ssh,
        "html asset refs",
        "python3 - <<'PY'\n"
        "import re\n"
        "for path in ['/tmp/wl-root.html','/tmp/wl-admin.html','/tmp/wl-root-https.html','/tmp/wl-admin-https.html']:\n"
        "    print('---', path)\n"
        "    try:\n"
        "        text=open(path,encoding='utf-8',errors='replace').read()\n"
        "    except FileNotFoundError:\n"
        "        print('missing')\n"
        "        continue\n"
        "    print(text[:200].replace('\\n',' '))\n"
        "    for m in re.findall(r'(?:src|href)=[\"\\']([^\"\\']+)', text)[:40]:\n"
        "        print(m)\n"
        "PY",
    )
    run(
        ssh,
        "suspect asset codes",
        "python3 - <<'PY'\n"
        "import re, subprocess\n"
        "paths=[]\n"
        "for file in ['/tmp/wl-root-https.html','/tmp/wl-admin-https.html']:\n"
        "    try: text=open(file,encoding='utf-8',errors='replace').read()\n"
        "    except FileNotFoundError: continue\n"
        "    paths += re.findall(r'(?:src|href)=[\"\\']([^\"\\']+)', text)\n"
        "for path in paths[:80]:\n"
        "    if path.startswith('http') or path.startswith('data:'): continue\n"
        "    url='https://127.0.0.1'+path\n"
        "    code=subprocess.check_output(['curl','-k','-s','-o','/dev/null','-w','%{http_code}','--max-time','10',url], text=True)\n"
        "    print(code, path)\n"
        "PY",
    )
    run(ssh, "dns and google", "getent hosts google.com; timeout 10 ping -c 3 google.com; echo ping_exit:$?; timeout 10 curl -I -L https://www.google.com; echo curl_exit:$?", timeout=60)
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
