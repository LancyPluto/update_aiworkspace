import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('8.134.93.203', username='root', password='KeChuangDianAi17728033019', timeout=10)

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
        print('STDERR:', err[:1000])

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

# ============================================================
# 1. CD pipeline: check latest git log on production
# ============================================================
run_cmd("1. Git log on production (latest 5 commits)",
    "cd /root/ai_tool_market && git log --oneline -5 2>/dev/null || echo 'No git repo'")

run_cmd("1b. Current branch",
    "cd /root/ai_tool_market && git branch --show-current 2>/dev/null")

# ============================================================
# 2. Container status and uptime (when did they last restart?)
# ============================================================
run_cmd("2. Docker container status",
    "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | grep supermarket")

# ============================================================
# 3. Worker: check if _declare_topology exists in the running code
# ============================================================
run_cmd("3. Worker: _declare_topology present?",
    "docker exec ai-supermarket-worker grep -n '_declare_topology' /app/task_queue/rabbitmq_consumer.py 2>/dev/null || echo 'NOT FOUND - old code'")

run_cmd("3b. Worker: import os present?",
    "docker exec ai-supermarket-worker head -5 /app/task_queue/rabbitmq_consumer.py 2>/dev/null")

# ============================================================
# 4. RabbitMQ: does ai.tool.normal queue exist now?
# ============================================================
run_cmd("4. RabbitMQ queues",
    "docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages consumers 2>/dev/null | grep -E 'ai\\.|^name'")

# ============================================================
# 5. Worker logs: still getting 404 or successfully consuming?
# ============================================================
run_cmd("5. Worker recent logs (last 30 lines)",
    "docker logs ai-supermarket-worker --tail 30 2>&1")

# ============================================================
# 6. Backend: check if COALESCE fix is deployed
# ============================================================
run_cmd("6. Backend: CommunityPostMapper COALESCE check",
    "docker exec ai-supermarket-backend find / -name 'CommunityPostMapper.class' 2>/dev/null | head -1")

# Check compiled class for the string pattern - if COALESCE is gone, the fix is deployed
run_cmd("6b. Backend JAR: COALESCE in CommunityPostMapper?",
    """docker exec ai-supermarket-backend sh -c 'jar_path=$(find /app -name "*.jar" | head -1); if [ -n "$jar_path" ]; then unzip -p "$jar_path" "BOOT-INF/classes/com/aiminilab/aitoolmarket/community/mapper/CommunityPostMapper.class" 2>/dev/null | strings | grep -i "COALESCE.*audit" | head -5 || echo "NO COALESCE(audit_status) found - FIX DEPLOYED"; else echo "No jar found"; fi'""")

# ============================================================
# 7. SQL migration 075: has it been applied?
# ============================================================
run_sql("7. SQL migration 075 status",
    """SELECT filename, applied_at FROM _sql_migration_log
WHERE filename LIKE '%075%'
ORDER BY applied_at DESC LIMIT 5;""")

# ============================================================
# 8. CDN URL fix: any broken URLs still in community_posts?
# ============================================================
run_sql("8. Broken CDN URLs remaining?",
    """SELECT COUNT(*) AS broken_cover_urls
FROM community_posts
WHERE cover_url LIKE 'https://wlcloudai.com/cdn/%';""")

# ============================================================
# 9. Frontend: check if dist has the new App.vue with KeepAlive
# ============================================================
run_cmd("9. Frontend dist: KeepAlive in compiled JS?",
    "grep -r 'KeepAlive\\|keepAliveIncludes\\|MaterialLibraryPage\\|CommunityDiscoverPage' /root/ai_tool_market/deploy/user-web-dist/assets/*.js 2>/dev/null | head -3 || echo 'NOT FOUND in dist'")

# ============================================================
# 10. Frontend: comparison animation CSS present?
# ============================================================
run_cmd("10. Frontend: comparison-wipe animation?",
    "grep -r 'comparison-wipe\\|comparison-pos\\|home-comparison' /root/ai_tool_market/deploy/user-web-dist/assets/*.css 2>/dev/null | head -3 || echo 'NOT FOUND in dist CSS'")

# ============================================================
# 11. INTERNAL_API_TOKEN consistency
# ============================================================
run_cmd("11. Token consistency across containers",
    """for c in ai-supermarket-backend ai-supermarket-worker ai-supermarket-agent-service; do
  token=$(docker exec $c printenv INTERNAL_API_TOKEN 2>/dev/null || echo 'N/A')
  echo "$c: $token"
done""")

# ============================================================
# 12. Deploy script / CD pipeline logs
# ============================================================
run_cmd("12. Recent deploy log",
    "ls -lt /root/ai_tool_market/deploy/*.log 2>/dev/null | head -3; echo '---'; tail -20 /root/ai_tool_market/deploy/deploy.log 2>/dev/null || echo 'No deploy log'")

run_cmd("12b. CD pipeline: last apply_sql_migrations output",
    "tail -20 /root/ai_tool_market/deploy/sql_migration.log 2>/dev/null || echo 'No SQL migration log'")

client.close()
print("\n\nDiagnostic complete.")
