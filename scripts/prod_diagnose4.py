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

# Check ai_result_resources columns
run_sql("ai_result_resources columns",
    "SHOW COLUMNS FROM ai_result_resources;")

# Seedance task result resources
run_sql("Seedance Task 71 results",
    "SELECT * FROM ai_result_resources WHERE task_id = 71;")

# Check stuck tasks (no model_configs JOIN)
run_sql("Stuck QUEUED/PROCESSING tasks",
    "SELECT id, task_no, status, progress, progress_message, model_config_id, created_at, updated_at FROM ai_tasks WHERE status IN ('QUEUED','PROCESSING') ORDER BY id DESC LIMIT 20;")

# Find correct table for model configs
run_sql("Tables with model in name",
    "SHOW TABLES LIKE '%%model%%';")

# Community posts with old /cdn/ URLs - show exact URLs
run_sql("Community posts with old /cdn/ URLs",
    "SELECT id, LEFT(title,30) as title, cover_url FROM community_posts WHERE cover_url LIKE 'https://wlcloudai.com/cdn/%%' AND status = 'PUBLISHED';")

# Also check if there are /cdn/ references in other tables
run_sql("ai_result_resources with /cdn/ URLs",
    "SELECT COUNT(*) as cnt FROM ai_result_resources WHERE url LIKE '%%/cdn/%%' OR url LIKE '%%wlcloudai.com/cdn/%%';")

# Check if feature/test-docs was merged to dev
run_sql("Recent git info",
    "")

client.close()
print("\nDone.")
