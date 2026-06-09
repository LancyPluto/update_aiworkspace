#!/usr/bin/env python3
"""Scan ai_tools.tool_name corruption and repair from config bundle."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BUNDLE = ROOT / "ai-tool-market-config-2026-06-08.json"

# Tools removed from bundle but still present as legacy/deleted rows.
FALLBACK_TOOL_NAMES: dict[str, str] = {
    "xiaohongshu_copywriting": "AI 小红书文案生成器",
}


def load_bundle_tools(bundle_path: Path) -> dict[str, str]:
    data = json.loads(bundle_path.read_text(encoding="utf-8"))
    tools = data.get("tools") or []
    mapping: dict[str, str] = {}
    for item in tools:
        code = (item.get("toolCode") or "").strip()
        name = (item.get("toolName") or "").strip()
        if code and name:
            mapping[code] = name
    return mapping


def query_db_rows(
    *,
    mysql_container: str,
    mysql_password: str,
    database: str,
) -> list[tuple[int, str, str]]:
    sql = "SELECT id, tool_code, tool_name FROM ai_tools ORDER BY id;"
    cmd = [
        "docker",
        "exec",
        mysql_container,
        "mysql",
        "-uroot",
        f"-p{mysql_password}",
        "--default-character-set=utf8mb4",
        "-N",
        "-B",
        database,
        "-e",
        sql,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip() or "mysql query failed")
    rows: list[tuple[int, str, str]] = []
    for line in proc.stdout.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        rows.append((int(parts[0]), parts[1], parts[2]))
    return rows


def is_corrupted_name(name: str) -> bool:
    if not name:
        return True
    if "?" in name:
        return True
    if any(ch in name for ch in ("Ã", "Â", "ç¿", "å±", "ï¿½")):
        return True
    try:
        name.encode("utf-8").decode("latin-1").encode("latin-1")  # noqa: S311
    except UnicodeError:
        return False
    return False


def escape_sql(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def main() -> int:
    parser = argparse.ArgumentParser(description="Fix corrupted ai_tools.tool_name from config bundle")
    parser.add_argument("--bundle", default=str(DEFAULT_BUNDLE))
    parser.add_argument("--mysql-container", default="ai-supermarket-mysql")
    parser.add_argument("--mysql-password", default="root123456")
    parser.add_argument("--database", default="ai_supermarket_v1")
    parser.add_argument("--apply", action="store_true", help="Execute SQL updates")
    parser.add_argument(
        "--include-fallback",
        action="store_true",
        default=True,
        help="Use built-in fallback names for legacy tool codes missing in bundle",
    )
    args = parser.parse_args()

    bundle_path = Path(args.bundle)
    if not bundle_path.is_file():
        print(f"Bundle not found: {bundle_path}", file=sys.stderr)
        return 1

    bundle_expected = load_bundle_tools(bundle_path)
    expected = bundle_expected
    if args.include_fallback:
        expected = {**FALLBACK_TOOL_NAMES, **bundle_expected}
    rows = query_db_rows(
        mysql_container=args.mysql_container,
        mysql_password=args.mysql_password,
        database=args.database,
    )

    fixes: list[dict[str, object]] = []
    missing_in_bundle: list[tuple[int, str, str]] = []
    ok = 0

    for tool_id, tool_code, current_name in rows:
        expected_name = expected.get(tool_code)
        if expected_name is None:
            if is_corrupted_name(current_name):
                missing_in_bundle.append((tool_id, tool_code, current_name))
            else:
                ok += 1
            continue
        if current_name == expected_name:
            ok += 1
            continue
        reason = "mismatch"
        if is_corrupted_name(current_name):
            reason = "corrupted"
        fixes.append(
            {
                "id": tool_id,
                "tool_code": tool_code,
                "current": current_name,
                "expected": expected_name,
                "reason": reason,
            }
        )

    print(f"bundle tools: {len(bundle_expected)}")
    if args.include_fallback and FALLBACK_TOOL_NAMES:
        print(f"fallback tools: {len(FALLBACK_TOOL_NAMES)}")
    print(f"db tools: {len(rows)}")
    print(f"already ok: {ok}")
    print(f"to fix: {len(fixes)}")
    print(f"corrupted but missing in bundle: {len(missing_in_bundle)}")
    print()

    if fixes:
        print("=== planned fixes ===")
        for item in fixes:
            print(
                f"- id={item['id']} code={item['tool_code']} reason={item['reason']}\n"
                f"  current: {item['current']}\n"
                f"  expected: {item['expected']}"
            )
        print()

    if missing_in_bundle:
        print("=== corrupted rows without bundle mapping (manual review) ===")
        for tool_id, tool_code, current_name in missing_in_bundle:
            print(f"- id={tool_id} code={tool_code} current={current_name}")
        print()

    if not args.apply:
        if fixes:
            print("Dry run only. Re-run with --apply to update database.")
        return 0

    if not fixes:
        print("Nothing to update.")
        return 0

    statements = [
        "SET NAMES utf8mb4;",
        *[
            (
                f"UPDATE ai_tools SET tool_name = '{escape_sql(str(item['expected']))}', "
                f"updated_at = NOW() WHERE id = {item['id']} AND tool_code = '{escape_sql(str(item['tool_code']))}';"
            )
            for item in fixes
        ],
    ]
    sql = "\n".join(statements)
    cmd = [
        "docker",
        "exec",
        "-i",
        args.mysql_container,
        "mysql",
        "-uroot",
        f"-p{args.mysql_password}",
        "--default-character-set=utf8mb4",
        args.database,
    ]
    proc = subprocess.run(cmd, input=sql, capture_output=True, text=True, encoding="utf-8")
    if proc.returncode != 0:
        print(proc.stderr or proc.stdout, file=sys.stderr)
        return 1

    print(f"Applied {len(fixes)} updates.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
