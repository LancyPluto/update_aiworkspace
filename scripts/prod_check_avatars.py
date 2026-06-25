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
    err = stderr.read().decode()
    print(f"\n=== {label} ===")
    if out.strip():
        print(out[:3000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

# Check avatar URLs - see if any use private/proxy patterns vs public CDN
run_sql("Avatar URL patterns",
    """SELECT
  CASE
    WHEN avatar_url LIKE 'https://cdn.wlcloudai.com/%' THEN 'CDN public'
    WHEN avatar_url LIKE 'https://wlcloudai.com/cdn/%' THEN 'Old CDN proxy (broken)'
    WHEN avatar_url LIKE '/api/v1/assets/private/%' THEN 'Private proxy'
    WHEN avatar_url LIKE 'https://wlcloudai-assets-public%' THEN 'Direct OSS public'
    WHEN avatar_url IS NULL OR avatar_url = '' THEN 'No avatar'
    ELSE CONCAT('Other: ', LEFT(avatar_url, 80))
  END AS url_pattern,
  COUNT(*) AS cnt
FROM users
GROUP BY url_pattern
ORDER BY cnt DESC;""")

# Check dist resources - static files in user-web/dist
run_sql("User count", "SELECT COUNT(*) as total_users FROM users;")

client.close()
print("\nDone.")
