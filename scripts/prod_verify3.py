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
    print(f"\n{'='*70}")
    print(f"  {label}")
    print(f"{'='*70}")
    if out.strip():
        print(out[:4000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

def run_sql(label, sql):
    write_cmd = f"cat > /tmp/q.sql << 'EOSQL'\n{sql}\nEOSQL"
    stdin, stdout, stderr = client.exec_command(write_cmd, timeout=10)
    stdout.read()
    exec_cmd = "docker cp /tmp/q.sql ai-supermarket-mysql:/tmp/q.sql && docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e 'SOURCE /tmp/q.sql' 2>/dev/null"
    stdin, stdout, stderr = client.exec_command(exec_cmd, timeout=30)
    out = stdout.read().decode()
    print(f"\n{'='*70}")
    print(f"  {label}")
    print(f"{'='*70}")
    if out.strip():
        print(out[:3000])
    else:
        print("(empty result)")

# 1. SQL migration log with CORRECT column name
run_sql("1. Migration log (correct column: name)",
    """SELECT name, applied_at FROM _sql_migration_log ORDER BY applied_at DESC LIMIT 15;""")

# 2. Check if 075 specifically was applied
run_sql("2. Migration 075 status",
    """SELECT name, applied_at FROM _sql_migration_log WHERE name LIKE '%075%';""")

# 3. Dead letter queue: peek at messages
run_cmd("3. Dead letter queue message details",
    """docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages 2>/dev/null | grep dead""")

# 4. Get dead letter queue messages content
run_cmd("4. Peek dead letter messages via rabbitmqadmin",
    """docker exec ai-supermarket-rabbitmq sh -c '
  rabbitmqadmin get queue=ai.tool.normal.dead count=5 2>/dev/null || echo "rabbitmqadmin not available, trying API..."
'""")

# 5. Try via management API
run_cmd("5. Dead letter messages via management API",
    """docker exec ai-supermarket-rabbitmq sh -c '
  curl -s -u guest:guest "http://localhost:15672/api/queues/%2F/ai.tool.normal.dead/get" \
    -H "content-type: application/json" \
    -d "{\"count\":5,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\"}" 2>/dev/null \
    | python3 -m json.tool 2>/dev/null || echo "Management API not available"
'""")

# 6. Worker logs - the actual DashScope error details
run_cmd("6. Worker DashScope error logs (last 100 lines, filtered)",
    """docker logs ai-supermarket-worker --tail 200 2>&1 | grep -A5 -B2 'resolution\|300x300\|DashScope\|dashscope.*failed\|MODEL_CALL_FAILED' | head -60""")

# 7. Check the failed tasks in DB
run_sql("7. Failed tasks 101 and 102",
    """SELECT t.id, t.task_no, t.status, t.error_code, t.error_message,
       t.tool_id, t.model_name, t.created_at
FROM ai_tasks t
WHERE t.id IN (101, 102);""")

# 8. Check the GitHub Actions workflow run status
run_cmd("8. Last deploy log entries",
    """tail -30 /root/ai_tool_market/deploy/logs/deploy-history.log 2>/dev/null || echo 'No deploy history log'""")

client.close()
print("\n\nDone.")
