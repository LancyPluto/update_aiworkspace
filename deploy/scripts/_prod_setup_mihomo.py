#!/usr/bin/env python3
"""Deploy mihomo on production.

Server mode (default): mixed-port + rule mode, NO TUN.
TUN hijacks the host default route and breaks inbound HTTP to nginx (external 403/connection reset).

Set MIHOMO_TUN=true only on non-web hosts (e.g. local dev machine).
"""
import json
import os
import sys

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
USER = os.environ.get("DEPLOY_USER", "root")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
SUB_URL = os.environ.get("MIHOMO_SUB_URL", "")
MIHOMO_TUN = os.environ.get("MIHOMO_TUN", "false").lower() in ("1", "true", "yes")
IMAGE = "docker.1ms.run/metacubex/mihomo:latest"


REMOTE_SCRIPT = r"""#!/usr/bin/env python3
import base64, json, os, re, subprocess, sys
from pathlib import Path

SUB_URL = os.environ["MIHOMO_SUB_URL"]
MIHOMO_TUN = os.environ.get("MIHOMO_TUN", "false").lower() in ("1", "true", "yes")
CONF_DIR = Path("/opt/mihomo")
CONF_DIR.mkdir(parents=True, exist_ok=True)

sub = subprocess.check_output(["curl", "-k", "-L", "--max-time", "30", "-sS", SUB_URL])
text = base64.b64decode(sub.strip() + b"=" * ((4 - len(sub.strip()) % 4) % 4)).decode("utf-8", "replace")

def b64decode_text(value):
    raw = value.encode()
    return base64.b64decode(raw + b"=" * ((4 - len(raw) % 4) % 4)).decode("utf-8", "replace")

def yaml_quote(value):
    text = str(value)
    return json.dumps(text, ensure_ascii=False)

proxies = []
for line in text.splitlines():
    line = line.strip()
    if not line.startswith("vmess://"):
        continue
    obj = json.loads(b64decode_text(line[8:]))
    name = obj.get("ps") or obj.get("name") or obj.get("add")
    proxies.append({
        "name": name,
        "server": obj.get("add"),
        "port": int(obj.get("port")),
        "uuid": obj.get("id"),
        "alterId": int(obj.get("aid") or 0),
        "cipher": "auto",
        "network": obj.get("net") or "tcp",
        "tls": bool(obj.get("tls")),
        "host": obj.get("host") or "",
        "path": obj.get("path") or "",
    })

us_re = re.compile(r"美国|US|United|America|LA|Los|洛杉矶|硅谷|圣何塞", re.I)
us_names = [p["name"] for p in proxies if us_re.search(p["name"])]
if not us_names:
    raise SystemExit("no US proxy nodes found")
preferred = next((name for name in us_names if "美国LA-优化-GPT" in name), us_names[0])

lines = [
    "mixed-port: 7890",
    "allow-lan: true",
    "bind-address: '*'",
    "mode: rule",
    "log-level: info",
    "ipv6: false",
    "geodata-mode: false",
    "geo-auto-update: false",
    "external-controller: 127.0.0.1:9090",
    "profile:",
    "  store-selected: true",
    "  store-fake-ip: true",
]
if MIHOMO_TUN:
    lines += [
        "tun:",
        "  enable: true",
        "  stack: system",
        "  device: mihomo-tun",
        "  dns-hijack:",
        "    - any:53",
        "  auto-route: true",
        "  auto-detect-interface: true",
        "dns:",
        "  enable: true",
        "  listen: 127.0.0.1:1053",
        "  ipv6: false",
        "  enhanced-mode: fake-ip",
        "  fake-ip-range: 198.18.0.1/16",
        "  nameserver:",
        "    - https://223.5.5.5/dns-query",
        "    - https://1.12.12.12/dns-query",
        "  fallback:",
        "    - https://8.8.8.8/dns-query",
        "    - https://1.1.1.1/dns-query",
        "  fallback-filter:",
        "    geoip: false",
        "    ipcidr:",
        "      - 240.0.0.0/4",
    ]
lines += ["proxies:"]
for proxy in proxies:
    lines += [
        f"  - name: {yaml_quote(proxy['name'])}",
        "    type: vmess",
        f"    server: {yaml_quote(proxy['server'])}",
        f"    port: {proxy['port']}",
        f"    uuid: {yaml_quote(proxy['uuid'])}",
        f"    alterId: {proxy['alterId']}",
        f"    cipher: {yaml_quote(proxy['cipher'])}",
        f"    network: {yaml_quote(proxy['network'])}",
        f"    tls: {'true' if proxy['tls'] else 'false'}",
    ]
    if proxy["network"] == "ws":
        lines += [
            "    ws-opts:",
            f"      path: {yaml_quote(proxy['path'] or '/')}",
        ]
        if proxy["host"]:
            lines += ["      headers:", f"        Host: {yaml_quote(proxy['host'])}"]
lines += [
    "proxy-groups:",
    "  - name: US-AUTO",
    "    type: url-test",
    "    url: https://www.gstatic.com/generate_204",
    "    interval: 300",
    "    tolerance: 80",
    "    proxies:",
]
for name in us_names:
    lines.append(f"      - {yaml_quote(name)}")
lines += [
    "  - name: PROXY",
    "    type: select",
    "    proxies:",
    "      - US-AUTO",
]
for name in us_names:
    lines.append(f"      - {yaml_quote(name)}")
lines += [
    "rules:",
    "  - IP-CIDR,8.134.93.203/32,DIRECT,no-resolve",
    "  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
    "  - DOMAIN-SUFFIX,wlcloudai.com,DIRECT",
    "  - DOMAIN-SUFFIX,aliyuncs.com,DIRECT",
    "  - DOMAIN-SUFFIX,aliyun.com,DIRECT",
    "  - DOMAIN-SUFFIX,taobao.com,DIRECT",
    "  - DOMAIN-SUFFIX,tmall.com,DIRECT",
    "  - DOMAIN-SUFFIX,qq.com,DIRECT",
    "  - DOMAIN-SUFFIX,weixin.qq.com,DIRECT",
    "  - DOMAIN-SUFFIX,baidu.com,DIRECT",
    "  - DOMAIN-SUFFIX,bdstatic.com,DIRECT",
    "  - DOMAIN-SUFFIX,360.cn,DIRECT",
    "  - DOMAIN-SUFFIX,jd.com,DIRECT",
    "  - DOMAIN-SUFFIX,bilibili.com,DIRECT",
    "  - DOMAIN-SUFFIX,douyin.com,DIRECT",
    "  - DOMAIN-SUFFIX,bytedance.com,DIRECT",
    "  - DOMAIN-SUFFIX,163.com,DIRECT",
    "  - DOMAIN-SUFFIX,126.com,DIRECT",
    "  - DOMAIN-SUFFIX,sina.com.cn,DIRECT",
    "  - DOMAIN-SUFFIX,cn,DIRECT",
    "  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
    "  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve",
    "  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
    "  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
    "  - IP-CIDR,224.0.0.0/4,DIRECT,no-resolve",
    "  - MATCH,PROXY",
    "",
]
(CONF_DIR / "config.yaml").write_text("\n".join(lines), encoding="utf-8")
(CONF_DIR / "subscription.url").write_text(SUB_URL + "\n", encoding="utf-8")
print("mode:", "tun" if MIHOMO_TUN else "mixed-port-server")
print("preferred:", preferred)
print("us_nodes:", ", ".join(us_names))
print("config:", CONF_DIR / "config.yaml")
"""


def run(ssh: paramiko.SSHClient, title: str, cmd: str, timeout: int = 300, env: dict[str, str] | None = None) -> int:
    print(f"\n=== {title} ===")
    if env:
        prefix = " ".join(f"{k}={json.dumps(v)}" for k, v in env.items())
        cmd = f"{prefix} {cmd}"
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out:
        print(out.rstrip())
    if err:
        print(err.rstrip(), file=sys.stderr)
    print(f"[exit={code}]")
    return code


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    if not SUB_URL:
        print("MIHOMO_SUB_URL required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)

    sftp = ssh.open_sftp()
    with sftp.open("/tmp/setup_mihomo_config.py", "w") as target:
        target.write(REMOTE_SCRIPT)
    sftp.close()

    gen_env = {"MIHOMO_SUB_URL": SUB_URL, "MIHOMO_TUN": "true" if MIHOMO_TUN else "false"}
    if run(ssh, "generate config", "python3 /tmp/setup_mihomo_config.py", env=gen_env) != 0:
        ssh.close()
        return 1
    if run(ssh, "config test", f"docker run --rm -v /opt/mihomo:/root/.config/mihomo {IMAGE} -t -d /root/.config/mihomo") != 0:
        ssh.close()
        return 1
    run(ssh, "stop old mihomo", "docker rm -f mihomo 2>/dev/null || true")

    if MIHOMO_TUN:
        start_cmd = (
            "docker run -d --name mihomo --restart unless-stopped "
            "--network host --cap-add NET_ADMIN --device /dev/net/tun "
            "-v /opt/mihomo:/root/.config/mihomo "
            f"{IMAGE} -d /root/.config/mihomo"
        )
        start_title = "start mihomo tun"
    else:
        start_cmd = (
            "docker run -d --name mihomo --restart unless-stopped "
            "--network host "
            "-v /opt/mihomo:/root/.config/mihomo "
            f"{IMAGE} -d /root/.config/mihomo"
        )
        start_title = "start mihomo mixed-port (server-safe)"

    if run(ssh, start_title, start_cmd) != 0:
        ssh.close()
        return 1

    run(ssh, "mihomo status", "docker ps --filter name=mihomo --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'; docker logs --tail 40 mihomo")
    run(
        ssh,
        "local site after mihomo",
        "curl -s -o /dev/null -w 'nginx:%{http_code}\\n' --max-time 15 http://127.0.0.1/; "
        "curl -s -o /dev/null -w 'admin:%{http_code}\\n' -L --max-time 20 http://127.0.0.1/admin; "
        "curl -s -o /dev/null -w 'healthz:%{http_code}\\n' --max-time 10 http://127.0.0.1/healthz",
    )
    run(
        ssh,
        "proxy egress via mixed-port",
        "timeout 25 curl -s -o /dev/null -w 'google:%{http_code}\\n' -x http://127.0.0.1:7890 -L https://www.google.com || echo google_failed; "
        "timeout 25 curl -s -o /dev/null -w 'openai:%{http_code}\\n' -x http://127.0.0.1:7890 -I https://api.openai.com || echo openai_failed",
        timeout=60,
    )
    run(
        ssh,
        "worker proxy egress",
        "docker exec ai-supermarket-worker sh -c 'timeout 25 curl -s -o /dev/null -w \"worker_google:%{http_code}\\n\" -x http://host.docker.internal:7890 -L https://www.google.com' "
        "|| echo worker_proxy_failed",
        timeout=60,
    )
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
