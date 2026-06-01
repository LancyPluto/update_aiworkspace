#!/usr/bin/env python3
"""Run deploy scripts using DEPLOY_PASSWORD or credentials from _remote.py."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def resolve_password() -> str | None:
    password = os.environ.get("DEPLOY_PASSWORD")
    if password:
        return password
    remote_py = ROOT / "_remote.py"
    if not remote_py.is_file():
        return None
    text = remote_py.read_text(encoding="utf-8")
    match = re.search(r"connect\([^,]+,\s*\d+,\s*[^,]+,\s*'([^']+)'", text)
    return match.group(1) if match else None


def main() -> int:
    password = resolve_password()
    if not password:
        print("Set DEPLOY_PASSWORD or provide _remote.py", file=sys.stderr)
        return 1
    os.environ["DEPLOY_PASSWORD"] = password
    os.environ.setdefault("DEPLOY_HOST", "8.134.93.203")
    scripts = sys.argv[1:]
    if not scripts:
        print("Usage: _run_with_remote_creds.py <script.py> ...", file=sys.stderr)
        return 1
    for rel in scripts:
        path = Path(rel)
        if not path.is_absolute():
            path = ROOT / rel
        print(f"\n=== Running {path.relative_to(ROOT)} ===\n")
        result = subprocess.run([sys.executable, str(path)], cwd=str(ROOT))
        if result.returncode != 0:
            return result.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
