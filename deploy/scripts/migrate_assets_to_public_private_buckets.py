#!/usr/bin/env python3
"""Migrate OSS assets from the legacy prod bucket into public/private buckets.

Default mode is dry-run. Use --apply to copy objects and update URLs, and
--delete-source to delete the legacy prod object after a successful copy.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

try:
    import oss2
except ImportError:
    print("oss2 required: pip install oss2", file=sys.stderr)
    raise

ROOT = Path(__file__).resolve().parents[2]
LEGACY_BUCKET = os.getenv("OSS_LEGACY_BUCKET", "wlcloudai-assets-prod").strip()
PUBLIC_BUCKET = os.getenv("OSS_PUBLIC_BUCKET", "wlcloudai-assets-public").strip()
PRIVATE_BUCKET = os.getenv("OSS_PRIVATE_BUCKET", "wlcloudai-assets-private").strip()
ENDPOINT = os.getenv("OSS_ENDPOINT", "oss-cn-guangzhou.aliyuncs.com").strip()
LEGACY_BASE = f"https://{LEGACY_BUCKET}.oss-cn-guangzhou.aliyuncs.com"
PUBLIC_BASE = os.getenv("ASSET_STORAGE_PUBLIC_BASE_URL", f"https://{PUBLIC_BUCKET}.oss-cn-guangzhou.aliyuncs.com").rstrip("/")
PRIVATE_BASE = os.getenv("ASSET_STORAGE_PRIVATE_BASE_URL", "/api/v1/assets/private").rstrip("/")
MEDIA_URL_RE = re.compile(r"(https?://[^\s\"'<>\])},]+|/generated/[^\s\"'<>\])},]+)")


@dataclass(frozen=True)
class UrlMove:
    table: str
    id_column: str
    row_id: int
    column: str
    old_url: str
    new_url: str
    target_bucket: str
    object_key: str


def mysql(sql: str) -> str:
    cmd = [
        "docker", "exec", "-i", "ai-supermarket-mysql",
        "mysql", "-uroot", "-p${MYSQL_ROOT_PASSWORD:-root}", "ai_tool_market",
        "--batch", "--raw", "--skip-column-names", "-e", sql,
    ]
    return subprocess.check_output(" ".join(cmd), shell=True, text=True, encoding="utf-8", errors="replace")


def bucket_client(name: str) -> oss2.Bucket:
    ak = (os.getenv("OSS_ACCESS_KEY_ID") or os.getenv("ALIYUN_ACCESS_KEY_ID") or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_ID") or "").strip()
    sk = (os.getenv("OSS_ACCESS_KEY_SECRET") or os.getenv("ALIYUN_ACCESS_KEY_SECRET") or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_SECRET") or "").strip()
    if not ak or not sk:
        raise RuntimeError("OSS_ACCESS_KEY_ID/SECRET or ALIYUN_ACCESS_KEY_ID/SECRET is required")
    endpoint = ENDPOINT if ENDPOINT.startswith(("http://", "https://")) else f"https://{ENDPOINT}"
    return oss2.Bucket(oss2.Auth(ak, sk), endpoint, name)


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def object_key_from_url(url: str) -> str | None:
    if not url:
        return None
    if url.startswith(LEGACY_BASE + "/"):
        return url[len(LEGACY_BASE) + 1 :]
    marker = f"{LEGACY_BUCKET}.oss-"
    if marker in url:
        path_start = url.find("/", url.find("://") + 3)
        return url[path_start + 1 :] if path_start > 0 else None
    return None


def target_for_key(key: str, *, published: bool) -> tuple[str, str]:
    public_prefixes = (
        "avatars/",
        "icons/",
        "tool-covers/",
        "customer-service/",
        "market-files/",
    )
    if published or key.startswith(public_prefixes):
        return PUBLIC_BUCKET, f"{PUBLIC_BASE}/{key}"
    return PRIVATE_BUCKET, f"{PRIVATE_BASE}/{key}"


def collect_moves() -> list[UrlMove]:
    moves: list[UrlMove] = []
    published_task_ids = {
        line.strip()
        for line in mysql(
            "SELECT task_id FROM community_posts WHERE status = 'PUBLISHED' AND COALESCE(audit_status,'APPROVED') = 'APPROVED';"
        ).splitlines()
        if line.strip()
    }
    rows = mysql(
        "SELECT id, task_id, status, COALESCE(audit_status,'APPROVED'), COALESCE(cover_url,''), COALESCE(media_url,'') "
        "FROM community_posts WHERE cover_url LIKE '%wlcloudai-assets-prod%' OR media_url LIKE '%wlcloudai-assets-prod%';"
    )
    for line in rows.splitlines():
        row_id, task_id, status, audit, cover_url, media_url = line.split("\t")
        published = status == "PUBLISHED" and audit == "APPROVED"
        if published:
            published_task_ids.add(task_id)
        for column, url in (("cover_url", cover_url), ("media_url", media_url)):
            key = object_key_from_url(url)
            if not key:
                continue
            bucket, new_url = target_for_key(key, published=published)
            moves.append(UrlMove("community_posts", "id", int(row_id), column, url, new_url, bucket, key))

    result_rows = mysql(
        "SELECT id, task_id, content_text FROM ai_result_resources WHERE content_text LIKE '%wlcloudai-assets-prod%';"
    )
    for line in result_rows.splitlines():
        row_id, task_id, content = line.split("\t", 2)
        published = task_id in published_task_ids
        for match in MEDIA_URL_RE.finditer(content):
            url = match.group(1)
            key = object_key_from_url(url)
            if not key:
                continue
            bucket, new_url = target_for_key(key, published=published)
            moves.append(UrlMove("ai_result_resources", "id", int(row_id), "content_text", url, new_url, bucket, key))

    for table, column in (
        ("user_upload_assets", "url"),
        ("agent_files", "storage_path"),
    ):
        if table == "agent_files":
            continue
        rows = mysql(f"SELECT id, {column} FROM {table} WHERE {column} LIKE '%wlcloudai-assets-prod%';")
        for line in rows.splitlines():
            row_id, url = line.split("\t", 1)
            key = object_key_from_url(url)
            if not key:
                continue
            bucket, new_url = target_for_key(key, published=False)
            moves.append(UrlMove(table, "id", int(row_id), column, url, new_url, bucket, key))
    return moves


def copy_objects(moves: list[UrlMove], *, apply: bool, delete_source: bool) -> None:
    source = bucket_client(LEGACY_BUCKET)
    clients = {
        PUBLIC_BUCKET: bucket_client(PUBLIC_BUCKET),
        PRIVATE_BUCKET: bucket_client(PRIVATE_BUCKET),
    }
    seen: set[tuple[str, str]] = set()
    for move in moves:
        marker = (move.target_bucket, move.object_key)
        if marker in seen:
            continue
        seen.add(marker)
        print(f"{'copy' if apply else 'dry-run copy'} oss://{LEGACY_BUCKET}/{move.object_key} -> oss://{move.target_bucket}/{move.object_key}")
        if not apply:
            continue
        clients[move.target_bucket].copy_object(LEGACY_BUCKET, move.object_key, move.object_key)
        if delete_source:
            source.delete_object(move.object_key)


def update_database(moves: list[UrlMove], *, apply: bool) -> None:
    for move in moves:
        if move.old_url == move.new_url:
            continue
        sql = (
            f"UPDATE {move.table} SET {move.column} = REPLACE({move.column}, "
            f"{sql_quote(move.old_url)}, {sql_quote(move.new_url)}) "
            f"WHERE {move.id_column} = {move.row_id};"
        )
        print(("sql " if apply else "dry-run sql ") + sql)
        if apply:
            mysql(sql)


def patch_env(*, apply: bool) -> None:
    block = {
        "ASSET_STORAGE_PROVIDER": "oss",
        "ASSET_STORAGE_PUBLIC_BASE_URL": PUBLIC_BASE,
        "ASSET_STORAGE_PRIVATE_BASE_URL": PRIVATE_BASE,
        "OSS_ENDPOINT": ENDPOINT,
        "OSS_PUBLIC_BUCKET": PUBLIC_BUCKET,
        "OSS_PRIVATE_BUCKET": PRIVATE_BUCKET,
        "OSS_LEGACY_BUCKET": LEGACY_BUCKET,
    }
    env_path = ROOT / ".env"
    current = env_path.read_text(encoding="utf-8", errors="replace") if env_path.exists() else ""
    for key in block:
        current = re.sub(rf"^{re.escape(key)}=.*\n?", "", current, flags=re.MULTILINE)
    if not current.endswith("\n"):
        current += "\n"
    current += "\n# OSS public/private asset storage\n"
    current += "\n".join(f"{key}={value}" for key, value in block.items()) + "\n"
    print("env patch:")
    print(json.dumps(block, ensure_ascii=False, indent=2))
    if apply:
        env_path.write_text(current, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="perform copies and DB/env updates")
    parser.add_argument("--delete-source", action="store_true", help="delete legacy prod object after successful copy")
    parser.add_argument("--skip-env", action="store_true")
    args = parser.parse_args()

    moves = collect_moves()
    print(f"planned url moves: {len(moves)}")
    copy_objects(moves, apply=args.apply, delete_source=args.delete_source)
    update_database(moves, apply=args.apply)
    if not args.skip_env:
        patch_env(apply=args.apply)
    if not args.apply:
        print("dry-run only; rerun with --apply after reviewing output")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
