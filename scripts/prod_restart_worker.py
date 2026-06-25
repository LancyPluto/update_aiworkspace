import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('8.134.93.203', username='root', password='KeChuangDianAi17728033019', timeout=10)

def run_cmd(label, cmd, timeout=60):
    stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    print(f"\n=== {label} ===")
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out.strip():
        print(out[:3000])
    if err.strip():
        print('STDERR:', err[:1000])

# Restart worker and agent-service to pick up correct INTERNAL_API_TOKEN
run_cmd("Restart worker",
    "cd /root/ai_tool_market/deploy && docker compose up -d --force-recreate worker", timeout=120)

run_cmd("Restart agent-service",
    "cd /root/ai_tool_market/deploy && docker compose up -d --force-recreate agent-service", timeout=120)

# Wait a moment then verify tokens
import time
time.sleep(5)

run_cmd("Verify worker token",
    "docker exec ai-supermarket-worker printenv INTERNAL_API_TOKEN 2>/dev/null")

run_cmd("Verify agent-service token",
    "docker exec ai-supermarket-agent-service printenv INTERNAL_API_TOKEN 2>/dev/null")

# Check if dead letter queue tasks can be retried
run_cmd("Dead letter queue status",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages consumers | grep -E 'dead|normal'")

client.close()
print("\nDone.")
