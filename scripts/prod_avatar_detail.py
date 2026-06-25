import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('8.134.93.203', username='root', password='KeChuangDianAi17728033019', timeout=10)

def run_sql(label, sql):
    write_cmd = f"cat > /tmp/q.sql << 'EOSQL'\n{sql}\nEOSQL"
    stdin, stdout, stderr = client.exec_command(write_cmd, timeout=10)
    stdout.read()
    exec_cmd = "docker cp /tmp/q.sql ai-supermarket-mysql:/tmp/q.sql && docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e 'SOURCE /tmp/q.sql' 2>/dev/null"
    stdin, stdout, stderr = client.exec_command(exec_cmd, timeout=30)
    out = stdout.read().decode()
    print(f"\n=== {label} ===")
    if out.strip():
        print(out[:3000])

# Private avatar details
run_sql("Private proxy avatars",
    """SELECT id, username, nickname, avatar_url
FROM users
WHERE avatar_url LIKE '/api/v1/assets/private/%';""")

# Check dist static files on production
def run_cmd(label, cmd):
    stdin, stdout, stderr = client.exec_command(cmd, timeout=15)
    out = stdout.read().decode()
    err = stderr.read().decode()
    print(f"\n=== {label} ===")
    if out.strip():
        print(out[:3000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

# Find images/videos in the deployed dist folder
run_cmd("Static media in dist",
    "find /root/ai_tool_market/deploy/user-web-dist/ -type f \\( -name '*.mp4' -o -name '*.webm' -o -name '*.jpg' -o -name '*.jpeg' -o -name '*.png' -o -name '*.webp' -o -name '*.gif' -o -name '*.svg' -o -name '*.ico' \\) 2>/dev/null | head -50")

run_cmd("Dist directory listing",
    "ls -la /root/ai_tool_market/deploy/user-web-dist/ 2>/dev/null || ls -la /root/ai_tool_market/user-web/dist/ 2>/dev/null || find /root/ai_tool_market -name 'dist' -type d 2>/dev/null | head -5")

client.close()
print("\nDone.")
