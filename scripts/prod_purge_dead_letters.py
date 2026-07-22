import os

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")

client = paramiko.SSHClient()
client.load_system_host_keys()
client.set_missing_host_key_policy(paramiko.RejectPolicy())
client.connect('8.134.93.203', username='root', password=password, timeout=10)

def run_cmd(label, cmd, timeout=30):
    stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode()
    err = stderr.read().decode()
    print(f"\n=== {label} ===")
    if out.strip():
        print(out[:3000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

# 1. Check dead letter queue before purge
run_cmd("Before: dead letter queue",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages 2>/dev/null | grep dead")

# 2. Purge the dead letter queue
run_cmd("Purge dead letter queue",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl purge_queue ai.tool.normal.dead 2>/dev/null")

# 3. Verify after purge
run_cmd("After: dead letter queue",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages 2>/dev/null | grep dead")

client.close()
print("\nDone.")
