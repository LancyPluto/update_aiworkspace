import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('8.134.93.203', username='root', password='KeChuangDianAi17728033019', timeout=10)

def run_cmd(label, cmd):
    stdin, stdout, stderr = client.exec_command(cmd)
    print(f"\n=== {label} ===")
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out.strip():
        print(out[:2000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

# Check worker container status
run_cmd("Worker container status",
    "docker ps --filter name=worker --format '{{.Names}} {{.Status}}'")

# Check worker logs (last 30 lines)
run_cmd("Worker logs (last 30 lines)",
    "docker logs --tail 30 ai-supermarket-worker 2>&1")

# Check task outbox for stuck tasks
run_cmd("Task outbox events for stuck tasks",
    'docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT * FROM task_outbox_events WHERE task_id IN (100, 73) ORDER BY id DESC LIMIT 10;"')

# Check model config 59 (HappyHorse stuck task)
run_cmd("Model config 59 details",
    'docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT id, model_name, display_name, execution_handler, is_enabled, is_deleted FROM agent_model_configs WHERE id = 59;"')

# Check model config 16 (other stuck task)
run_cmd("Model config 16 details",
    'docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT id, model_name, display_name, execution_handler, is_enabled, is_deleted FROM agent_model_configs WHERE id = 16;"')

# Check if feature/test-docs is on dev
run_cmd("Git branches on production",
    "cd /root/ai-tool-market && git branch -a --contains dd7cfb32 2>/dev/null || echo 'commit not found'")

# Check RabbitMQ queue status
run_cmd("RabbitMQ queues",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages consumers 2>/dev/null || echo 'rabbitmq check failed'")

client.close()
print("\nDone.")
