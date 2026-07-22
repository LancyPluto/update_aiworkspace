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

# Check INTERNAL_API_TOKEN in both containers
run_cmd("Backend INTERNAL_API_TOKEN",
    "docker exec ai-supermarket-backend printenv INTERNAL_API_TOKEN 2>/dev/null || echo 'NOT SET'")

run_cmd("Worker INTERNAL_API_TOKEN",
    "docker exec ai-supermarket-worker printenv INTERNAL_API_TOKEN 2>/dev/null || echo 'NOT SET'")

# Check production .env
run_cmd("Production .env INTERNAL_API_TOKEN",
    "grep INTERNAL_API_TOKEN /root/deploy/.env 2>/dev/null || find / -name '.env' -path '*/deploy/*' 2>/dev/null | head -3")

# Check deploy directory
run_cmd("Deploy directory contents",
    "ls /root/deploy/ 2>/dev/null || ls /opt/deploy/ 2>/dev/null || find / -name 'docker-compose.yml' -path '*/deploy/*' 2>/dev/null | head -5")

# Check the dead letter queue messages
run_cmd("Dead letter queue messages",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages consumers | grep dead")

# Also check if worker has correct backend URL
run_cmd("Worker BACKEND_INTERNAL_BASE_URL",
    "docker exec ai-supermarket-worker printenv BACKEND_INTERNAL_BASE_URL 2>/dev/null || echo 'NOT SET'")

client.close()
print("\nDone.")
