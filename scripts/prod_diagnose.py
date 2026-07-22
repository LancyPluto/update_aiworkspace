import os

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")

client = paramiko.SSHClient()
client.load_system_host_keys()
client.set_missing_host_key_policy(paramiko.RejectPolicy())
client.connect('8.134.93.203', username='root', password=password, timeout=10)

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

# Issue 2: Seedance 1.5 stuck task
run_sql("Seedance Task T20260618250090BB4A",
    "SELECT id, task_id, status, progress, provider_code, model_name, result_url, user_deleted, created_at, updated_at FROM tasks WHERE task_id = 'T20260618250090BB4A';")

# Issue 8: HappyHorse stuck tasks today
run_sql("HappyHorse Tasks (today)",
    "SELECT id, task_id, status, progress, provider_code, model_name, created_at, updated_at FROM tasks WHERE created_at >= '2026-06-25' AND provider_code LIKE '%happyhorse%' ORDER BY id DESC LIMIT 10;")

# Also check all recent stuck tasks
run_sql("All QUEUED/PROCESSING tasks older than 1 hour",
    "SELECT id, task_id, status, progress, provider_code, model_name, created_at, updated_at FROM tasks WHERE status IN ('QUEUED','PROCESSING') AND updated_at < NOW() - INTERVAL 1 HOUR ORDER BY id DESC LIMIT 20;")

# Issue 9: Check search - community posts with broken cover URLs
run_sql("Community posts with empty or broken cover URLs (sample)",
    "SELECT id, title, cover_url, modality, audit_status FROM community_posts WHERE status = 'PUBLISHED' AND (cover_url IS NULL OR cover_url = '') LIMIT 10;")

run_sql("Community posts cover URL patterns",
    "SELECT CASE WHEN cover_url LIKE '/cdn/%' THEN '/cdn/...' WHEN cover_url LIKE '/generated/%' THEN '/generated/...' WHEN cover_url LIKE 'https://wlcloudai-assets%' THEN 'oss-public' WHEN cover_url IS NULL OR cover_url = '' THEN 'EMPTY' ELSE 'other' END AS url_type, COUNT(*) AS cnt FROM community_posts WHERE status = 'PUBLISHED' GROUP BY url_type;")

# Issue 7: Check if consoleCookie column exists
run_sql("Check consoleCookie column",
    "SHOW COLUMNS FROM model_vendor_accounts LIKE 'console_cookie%';")

# Issue 3: Check audit_status index
run_sql("Check community_posts indexes",
    "SHOW INDEX FROM community_posts WHERE Column_name = 'audit_status';")

client.close()
print("\nDone.")
