import os

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")

client = paramiko.SSHClient()
client.load_system_host_keys()
client.set_missing_host_key_policy(paramiko.RejectPolicy())
client.connect('8.134.93.203', username='root', password=password, timeout=10)

def run_cmd(label, cmd):
    stdin, stdout, stderr = client.exec_command(cmd)
    print(f"\n=== {label} ===")
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out.strip():
        print(out[:3000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

# Check what the .env says
run_cmd("Production .env INTERNAL_API_TOKEN value",
    "grep INTERNAL_API_TOKEN /root/ai_tool_market/deploy/.env")

# Check all running container tokens
run_cmd("All container INTERNAL_API_TOKEN values",
    """for c in $(docker ps --format '{{.Names}}' | grep supermarket); do echo "$c: $(docker exec $c printenv INTERNAL_API_TOKEN 2>/dev/null || echo 'N/A')"; done""")

client.close()
print("\nDone.")
