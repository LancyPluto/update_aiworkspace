#!/usr/bin/env python3
import os
import sys
import paramiko

def main():
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    password = os.environ.get("DEPLOY_PASSWORD")
    cmd = sys.argv[1] if len(sys.argv) > 1 else "uname -a"
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username=user, password=password, timeout=30)
    _, stdout, stderr = ssh.exec_command(cmd, timeout=300)
    out = stdout.read().decode()
    err = stderr.read().decode()
    code = stdout.channel.recv_exit_status()
    if out:
        print(out, end="" if out.endswith("\n") else "\n")
    if err:
        print(err, file=sys.stderr, end="" if err.endswith("\n") else "\n")
    ssh.close()
    return code

if __name__ == "__main__":
    raise SystemExit(main())
