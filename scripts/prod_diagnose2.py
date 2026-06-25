import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect('8.134.93.203', username='root', password='KeChuangDianAi17728033019', timeout=10)

def run_sql(label, sql):
    cmd = f'docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "{sql}"'
    stdin, stdout, stderr = client.exec_command(cmd)
    print(f"\n=== {label} ===")
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out.strip():
        print(out)
    if 'ERROR' in err:
        print('ERROR:', err)

# Issue 2: Seedance task
run_sql("Seedance Task T20260618250090BB4A",
    "SELECT id, task_id, status, progress, provider_code, model_name, user_deleted, created_at, updated_at FROM ai_tasks WHERE task_id = 'T20260618250090BB4A';")

# Also check result resources for this task
run_sql("Seedance Task Results",
    "SELECT r.id, r.task_id, r.resource_type, r.resource_url, r.created_at FROM ai_result_resources r WHERE r.task_id = (SELECT id FROM ai_tasks WHERE task_id = 'T20260618250090BB4A' LIMIT 1);")

# Issue 8: HappyHorse tasks
run_sql("HappyHorse Tasks (recent)",
    "SELECT id, task_id, status, progress, provider_code, model_name, created_at, updated_at FROM ai_tasks WHERE provider_code LIKE '%%happyhorse%%' ORDER BY id DESC LIMIT 10;")

# Also check by task_id pattern from screenshot
run_sql("Task T20260625 pattern",
    "SELECT id, task_id, status, progress, provider_code, model_name, created_at, updated_at FROM ai_tasks WHERE task_id LIKE 'T2026062518%%' ORDER BY id DESC LIMIT 10;")

# All stuck tasks
run_sql("All QUEUED/PROCESSING tasks older than 1 hour",
    "SELECT id, task_id, status, progress, provider_code, model_name, created_at, updated_at FROM ai_tasks WHERE status IN ('QUEUED','PROCESSING') AND updated_at < NOW() - INTERVAL 1 HOUR ORDER BY id DESC LIMIT 20;")

# Issue 9: Check community post cover URLs
run_sql("Sample community post cover URLs",
    "SELECT id, LEFT(title,20) as title, LEFT(cover_url,80) as cover_url, modality FROM community_posts WHERE status = 'PUBLISHED' LIMIT 15;")

# Check what user-web search actually queries
run_sql("Community posts with /cdn/ URLs",
    "SELECT COUNT(*) as cnt FROM community_posts WHERE cover_url LIKE '/cdn/%%';")

run_sql("Community posts with NULL/empty cover_url",
    "SELECT COUNT(*) as cnt FROM community_posts WHERE cover_url IS NULL OR cover_url = '';")

client.close()
print("\nDone.")
