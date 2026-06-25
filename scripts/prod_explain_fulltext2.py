import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('8.134.93.203', username='root', password='KeChuangDianAi17728033019', timeout=10)

def run_sql(label, sql):
    # Write SQL to temp file, then execute via mysql < file
    write_cmd = f"cat > /tmp/explain_query.sql << 'EOSQL'\n{sql}\nEOSQL"
    stdin, stdout, stderr = client.exec_command(write_cmd, timeout=10)
    stdout.read()

    exec_cmd = "docker exec -i ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 < /tmp/explain_query.sql 2>/dev/null"
    # Use docker cp instead
    exec_cmd = "docker cp /tmp/explain_query.sql ai-supermarket-mysql:/tmp/explain_query.sql && docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e 'SOURCE /tmp/explain_query.sql' 2>/dev/null"
    stdin, stdout, stderr = client.exec_command(exec_cmd, timeout=30)
    out = stdout.read().decode()
    err = stderr.read().decode()
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")
    if out.strip():
        print(out[:3000])
    if err.strip() and 'Warning' not in err:
        print('STDERR:', err[:500])

# Table sizes
run_sql("Table row counts",
    """SELECT table_name, table_rows, ROUND(data_length/1024/1024,1) AS data_mb
FROM information_schema.tables
WHERE table_schema='ai_supermarket_v1'
AND table_name IN ('community_posts','ai_tools','users','billing_usage_logs',
'agent_workspace_memory_items','agent_tool_calls','agent_messages')
ORDER BY table_rows DESC;""")

# EXPLAIN: community_posts
run_sql("EXPLAIN: community_posts LIKE search",
    """EXPLAIN SELECT id, title, description FROM community_posts
WHERE (audit_status IS NULL OR audit_status = 'APPROVED')
AND (title LIKE '%test%' OR description LIKE '%test%'
OR tool_name LIKE '%test%' OR tool_code LIKE '%test%')
ORDER BY id DESC LIMIT 20;""")

# EXPLAIN: ai_tools
run_sql("EXPLAIN: ai_tools LIKE search",
    """EXPLAIN SELECT id, tool_code, tool_name FROM ai_tools
WHERE LOWER(tool_code) LIKE '%test%'
OR LOWER(tool_name) LIKE '%test%'
OR LOWER(description) LIKE '%test%'
ORDER BY sort_order ASC, id DESC LIMIT 20;""")

# EXPLAIN: users
run_sql("EXPLAIN: users LIKE search",
    """EXPLAIN SELECT id, username, nickname FROM users
WHERE LOWER(username) LIKE '%test%'
OR LOWER(nickname) LIKE '%test%'
OR phone LIKE '%test%'
OR LOWER(email) LIKE '%test%'
LIMIT 20;""")

# EXPLAIN: billing_usage_logs
run_sql("EXPLAIN: billing_usage_logs model_name LIKE",
    """EXPLAIN SELECT id, model_name FROM billing_usage_logs
WHERE LOWER(model_name) LIKE '%gpt%'
ORDER BY id DESC LIMIT 20;""")

# FULLTEXT indexes
run_sql("Existing FULLTEXT indexes",
    """SELECT table_name, index_name, column_name, index_type
FROM information_schema.statistics
WHERE table_schema='ai_supermarket_v1' AND index_type='FULLTEXT';""")

client.close()
print("\nDone.")
