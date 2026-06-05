#!/usr/bin/env python3
import json
import os
import sys

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")


def run(ssh, cmd: str, timeout: int = 120) -> str:
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if err.strip():
        print(err.strip(), file=sys.stderr)
    return out.strip()


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)

    print("=== HTTP ===")
    for label, url in [
        ("root", "http://127.0.0.1/"),
        ("admin", "http://127.0.0.1/admin/"),
        ("backend", "http://127.0.0.1:8080/actuator/health"),
    ]:
        code = run(ssh, f"curl -s -o /dev/null -w '%{{http_code}}' --max-time 15 {url}")
        print(f"{label}: {code}")

    print("\n=== GPT-Image models ===")
    sql = (
        "SELECT id,display_name,model_name,billing_unit,"
        "input_token_price_per_1m,output_token_price_per_1m,enabled "
        "FROM agent_model_configs WHERE LOWER(model_name) LIKE '%gpt-image%' "
        "OR LOWER(display_name) LIKE '%image2%' OR LOWER(display_name) LIKE '%gpt-image%';"
    )
    out = run(
        ssh,
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        f"mysql -uroot -proot123456 ai_supermarket_v1 -e \"{sql}\"",
    )
    print(out)

    print("\n=== ofox_gpt_image2 tool ===")
    sql2 = (
        "SELECT id,tool_code,estimated_credit_cost,model_config_id,status "
        "FROM ai_tools WHERE tool_code='ofox_gpt_image2';"
    )
    out2 = run(
        ssh,
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        f"mysql -uroot -proot123456 ai_supermarket_v1 -e \"{sql2}\"",
    )
    print(out2)

    print("\n=== tool API estimate ===")
    raw = run(ssh, "curl -s 'http://127.0.0.1:8080/api/v1/tools?pageNo=1&pageSize=50'")
    try:
        data = json.loads(raw)
        tools = data.get("data", {}).get("list", [])
        hit = [t for t in tools if t.get("toolCode") == "ofox_gpt_image2"]
        print(json.dumps(hit, ensure_ascii=False, indent=2))
    except json.JSONDecodeError:
        print(raw[:500])

    print("\n=== last_test columns ===")
    out3 = run(
        ssh,
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        "mysql -uroot -proot123456 ai_supermarket_v1 -e "
        "\"SHOW COLUMNS FROM agent_model_configs LIKE 'last_test%';\"",
    )
    print(out3)

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
