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

# ============================================================
# Frontend: find where compiled JS/CSS actually lives
# ============================================================
run_cmd("A1. User-web dist structure",
    "find /root/ai_tool_market/deploy/user-web-dist/ -type f -name '*.js' -o -name '*.css' 2>/dev/null | head -20")

run_cmd("A2. User-web container: compiled assets location",
    "docker exec ai-supermarket-user-web find /app/dist -type f -name '*.js' -o -name '*.css' 2>/dev/null | head -20")

run_cmd("A3. Nginx config: where does it serve frontend from?",
    "docker exec ai-supermarket-nginx cat /etc/nginx/conf.d/default.conf 2>/dev/null | head -40")

# ============================================================
# Frontend: check for KeepAlive in actual compiled files
# ============================================================
run_cmd("A4. KeepAlive in user-web container JS",
    "docker exec ai-supermarket-user-web sh -c 'grep -rl \"keepAliveIncludes\\|MaterialLibraryPage\\|CommunityDiscoverPage\" /app/dist/assets/*.js 2>/dev/null || echo NOT_FOUND'")

run_cmd("A5. comparison-wipe in user-web container CSS",
    "docker exec ai-supermarket-user-web sh -c 'grep -rl \"comparison-wipe\\|comparison-pos\\|home-comparison\" /app/dist/assets/*.css 2>/dev/null || echo NOT_FOUND'")

# ============================================================
# SQL migration: check if 075 file exists and migration log
# ============================================================
run_cmd("B1. SQL migration files on server",
    "ls -la /root/ai_tool_market/sql/075* 2>/dev/null || echo 'File not found'")

run_cmd("B2. Migration script location",
    "ls -la /root/ai_tool_market/deploy/apply_sql_migrations.sh 2>/dev/null || echo 'Script not found'")

run_sql("B3. All migration log entries (last 10)",
    """SELECT filename, applied_at FROM _sql_migration_log
ORDER BY applied_at DESC LIMIT 10;""")

# ============================================================
# Backend: verify COALESCE fix via javap or class file
# ============================================================
run_cmd("C1. Backend: check annotation strings in class",
    """docker exec ai-supermarket-backend sh -c '
  jar=$(find /app -name "*.jar" | head -1)
  if [ -n "$jar" ]; then
    unzip -p "$jar" "BOOT-INF/classes/com/aiminilab/aitoolmarket/community/mapper/CommunityPostMapper.class" 2>/dev/null | grep -a -o "COALESCE[^)]*)" | head -5 || echo "NO_COALESCE_FOUND"
  else
    echo "No jar"
  fi
'""")

run_cmd("C2. Backend: check TaskMapper for deferred JOIN",
    """docker exec ai-supermarket-backend sh -c '
  jar=$(find /app -name "*.jar" | head -1)
  if [ -n "$jar" ]; then
    unzip -p "$jar" "BOOT-INF/classes/com/aiminilab/aitoolmarket/task/mapper/TaskMapper.class" 2>/dev/null | grep -ao "INNER JOIN" | head -3 || echo "NO_INNER_JOIN_FOUND"
  else
    echo "No jar"
  fi
'""")

# ============================================================
# Backend: check BillingUsageLogMapper for deferred JOIN
# ============================================================
run_cmd("C3. Backend: BillingUsageLogMapper deferred JOIN",
    """docker exec ai-supermarket-backend sh -c '
  jar=$(find /app -name "*.jar" | head -1)
  if [ -n "$jar" ]; then
    unzip -p "$jar" "BOOT-INF/classes/com/aiminilab/aitoolmarket/admin/mapper/BillingUsageLogMapper.class" 2>/dev/null | grep -ao "INNER JOIN" | head -3 || echo "NO_INNER_JOIN_FOUND"
  else
    echo "No jar"
  fi
'""")

# ============================================================
# CD pipeline: how does the deploy work?
# ============================================================
run_cmd("D1. Deploy directory contents",
    "ls /root/ai_tool_market/deploy/ | head -20")

run_cmd("D2. Crontab or systemd for CD",
    "crontab -l 2>/dev/null | grep -i 'deploy\\|git\\|pull' || echo 'No cron entries for deploy'")

run_cmd("D3. GitHub Actions webhook or ci script",
    "ls /root/ai_tool_market/deploy/ci_* 2>/dev/null; ls /root/ai_tool_market/.github/workflows/*.yml 2>/dev/null | head -5 || echo 'No CI files'")

client.close()
print("\n\nDiagnostic complete.")
