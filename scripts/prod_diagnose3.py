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

# Issue 2: Seedance task - use task_no column
run_sql("Seedance Task T20260618250090BB4A",
    "SELECT t.id, t.task_no, t.status, t.progress, t.progress_message, t.error_code, t.error_message, t.created_at, t.finished_at FROM ai_tasks t WHERE t.task_no = 'T20260618250090BB4A';")

# Check result resources
run_sql("Seedance Task Result Resources",
    "SELECT r.id, r.task_id, r.resource_key, r.created_at FROM ai_result_resources r WHERE r.task_id = (SELECT id FROM ai_tasks WHERE task_no = 'T20260618250090BB4A' LIMIT 1);")

# Issue 8: Find HappyHorse tasks - via model_config join
run_sql("Recent stuck tasks (QUEUED/PROCESSING)",
    "SELECT t.id, t.task_no, t.status, t.progress, t.progress_message, t.error_code, mc.model_name, t.created_at, t.finished_at FROM ai_tasks t LEFT JOIN model_configs mc ON mc.id = t.model_config_id WHERE t.status IN ('QUEUED','PROCESSING') ORDER BY t.id DESC LIMIT 20;")

# Check user_deleted field
run_sql("ai_tasks columns check",
    "SHOW COLUMNS FROM ai_tasks;")

# Issue 9: Count community posts by URL pattern
run_sql("Community posts URL pattern breakdown",
    "SELECT CASE WHEN cover_url LIKE 'https://wlcloudai.com/cdn/%%' THEN 'wlcloudai.com/cdn' WHEN cover_url LIKE 'https://cdn.wlcloudai.com/%%' THEN 'cdn.wlcloudai.com' WHEN cover_url LIKE '/generated/%%' THEN '/generated' WHEN cover_url IS NULL OR cover_url = '' THEN 'EMPTY' ELSE 'other' END AS url_type, COUNT(*) AS cnt FROM community_posts WHERE status = 'PUBLISHED' GROUP BY url_type ORDER BY cnt DESC;")

client.close()
print("\nDone.")
