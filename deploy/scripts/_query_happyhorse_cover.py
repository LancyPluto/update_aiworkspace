#!/usr/bin/env python3
import os, paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('8.134.93.203', username='root', password=os.environ['DEPLOY_PASSWORD'], timeout=30)
cmd = """docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -N -e "SELECT tool_code, cover_url FROM ai_tools WHERE tool_code LIKE 'happyhorse%'" """
_, o, _ = ssh.exec_command(cmd)
print(o.read().decode())
ssh.close()
