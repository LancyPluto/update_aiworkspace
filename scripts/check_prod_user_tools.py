#!/usr/bin/env python3
import os
import sys

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    print("DEPLOY_PASSWORD required", file=sys.stderr)
    sys.exit(1)

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=password, timeout=30)

cmd = r"""docker exec ai-supermarket-mysql mysql -uroot -proot123456 --default-character-set=utf8mb4 -N -B -e "
SELECT t.tool_code, t.status, t.tool_type, c.category_code, w.status AS workflow_status
FROM ai_tools t
LEFT JOIN tool_categories c ON c.id = t.category_id
LEFT JOIN tool_workflows w ON w.tool_id = t.id
WHERE t.is_deleted = 0
  AND (c.category_code = 'agent' OR t.tool_code LIKE '%agent%' OR w.id IS NOT NULL)
ORDER BY t.tool_code;" ai_supermarket_v1 2>/dev/null"""

_, stdout, stderr = ssh.exec_command(cmd, timeout=60)
print(stdout.read().decode("utf-8", errors="replace"))
ssh.close()
