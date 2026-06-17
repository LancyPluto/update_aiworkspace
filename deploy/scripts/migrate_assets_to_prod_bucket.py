#!/usr/bin/env python3
"""Migrate production assets: local volume + dev OSS bucket -> prod OSS bucket.

Run on the production host (or via deploy/scripts/run_prod_asset_migration.py):
  python3 deploy/scripts/migrate_assets_to_prod_bucket.py
  python3 deploy/scripts/migrate_assets_to_prod_bucket.py --dry-run

Requires: oss2 (worker container has it), MySQL via docker, .env with OSS AK.
"""
from __future__ import annotations

import argparse
import mimetypes
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

try:
    from dotenv import load_dotenv
except ImportError:
    load_dotenv = None

if load_dotenv is not None:
    load_dotenv(ROOT / ".env")

try:
    import oss2
except ImportError:
    print("oss2 required: pip install oss2", file=sys.stderr)
    sys.exit(1)

DEV_BUCKET = "wlcloudai-assets-dev"
PROD_BUCKET = "wlcloudai-assets-prod"
ENDPOINT = os.getenv("OSS_ENDPOINT", "oss-cn-guangzhou.aliyuncs.com").strip()
if not ENDPOINT.startswith("http"):
    ENDPOINT = f"https://{ENDPOINT}"

PROD_BASE = f"https://{PROD_BUCKET}.oss-cn-guangzhou.aliyuncs.com"
DEV_BASE = f"https://{DEV_BUCKET}.oss-cn-guangzhou.aliyuncs.com"
LOCAL_MEDIA_DIR = Path(os.getenv("GENERATED_MEDIA_DIR", str(ROOT / "data" / "generated-media")))
MYSQL_CONTAINER = os.getenv("MYSQL_CONTAINER", "ai-supermarket-mysql")
MYSQL_DB = os.getenv("MYSQL_DATABASE", "ai_supermarket_v1")
MYSQL_PASSWORD = os.getenv("MYSQL_PASSWORD", "root123456")


def _ak_sk() -> tuple[str, str]:
    ak = (
        os.getenv("OSS_ACCESS_KEY_ID")
        or os.getenv("ALIYUN_ACCESS_KEY_ID")
        or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_ID")
        or ""
    ).strip()
    sk = (
        os.getenv("OSS_ACCESS_KEY_SECRET")
        or os.getenv("ALIYUN_ACCESS_KEY_SECRET")
        or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_SECRET")
        or ""
    ).strip()
    if not ak or not sk:
        raise SystemExit("Missing OSS access key in environment")
    return ak, sk


def _bucket(name: str) -> oss2.Bucket:
    ak, sk = _ak_sk()
    return oss2.Bucket(oss2.Auth(ak, sk), ENDPOINT, name)


def _guess_content_type(path: Path) -> str:
    guessed, _ = mimetypes.guess_type(path.name)
    return guessed or "application/octet-stream"


def copy_dev_bucket_to_prod(*, dry_run: bool) -> tuple[int, int]:
    dev = _bucket(DEV_BUCKET)
    prod = _bucket(PROD_BUCKET)
    copied = skipped = 0
    for obj in oss2.ObjectIterator(dev):
        key = obj.key
        if dry_run:
            print(f"[dry-run] copy oss://{DEV_BUCKET}/{key} -> oss://{PROD_BUCKET}/{key}")
            copied += 1
            continue
        try:
            prod.head_object(key)
            skipped += 1
            continue
        except oss2.exceptions.NoSuchKey:
            pass
        except Exception:
            pass
        prod.copy_object(DEV_BUCKET, key, key)
        copied += 1
        print(f"copied oss key: {key}")
    return copied, skipped


def upload_local_to_prod(*, dry_run: bool) -> int:
    root = LOCAL_MEDIA_DIR.resolve()
    if not root.is_dir():
        print(f"local media dir missing: {root}")
        return 0
    prod = _bucket(PROD_BUCKET)
    uploaded = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        key = path.relative_to(root).as_posix()
        if dry_run:
            print(f"[dry-run] upload {path} -> oss://{PROD_BUCKET}/{key}")
            uploaded += 1
            continue
        try:
            meta = prod.head_object(key)
            if int(meta.content_length) == path.stat().st_size:
                continue
        except oss2.exceptions.NoSuchKey:
            pass
        except Exception:
            pass
        with path.open("rb") as fh:
            prod.put_object(key, fh, headers={"Content-Type": _guess_content_type(path)})
        uploaded += 1
        print(f"uploaded local: {key}")
    return uploaded


def _sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def _replace_expr(expr: str) -> str:
    replacements = [
        (DEV_BASE, PROD_BASE),
        ("http://backend:8080/generated/", f"{PROD_BASE}/"),
        ("https://wlcloudai.com/generated/", f"{PROD_BASE}/"),
        ("http://wlcloudai.com/generated/", f"{PROD_BASE}/"),
        ("/generated/", f"{PROD_BASE}/"),
    ]
    current = expr
    for old, new in replacements:
        current = f"REPLACE({current}, '{_sql_escape(old)}', '{_sql_escape(new)}')"
    return current


def _needs_migration_predicate(column: str) -> str:
    return (
        f"({column} LIKE '%/generated/%' OR {column} LIKE '%wlcloudai-assets-dev%' "
        f"OR {column} LIKE '%backend:8080/generated%')"
    )


def update_database(*, dry_run: bool) -> None:
    text_updates: list[tuple[str, str, str]] = [
        ("users", "avatar_url", "avatar_url IS NOT NULL AND avatar_url != ''"),
        ("ai_tools", "cover_url", "cover_url IS NOT NULL AND cover_url != ''"),
        ("system_settings", "setting_value", "setting_value IS NOT NULL AND setting_value != ''"),
        ("ai_result_resources", "content_text", "content_text IS NOT NULL AND content_text != ''"),
        ("ai_task_inputs", "value_text", "value_text IS NOT NULL AND value_text != ''"),
        ("user_upload_assets", "url", "url IS NOT NULL AND url != ''"),
        ("user_upload_assets", "storage_path", "storage_path IS NOT NULL AND storage_path != ''"),
        ("community_posts", "cover_url", "cover_url IS NOT NULL AND cover_url != ''"),
        ("community_posts", "media_url", "media_url IS NOT NULL AND media_url != ''"),
        ("agent_files", "storage_path", "storage_path IS NOT NULL AND storage_path != ''"),
        ("ai_market_tools", "icon_url", "icon_url IS NOT NULL AND icon_url != ''"),
        ("agent_messages", "content_text", "content_text IS NOT NULL AND content_text != ''"),
        ("credit_recharge_orders", "qr_code_url", "qr_code_url IS NOT NULL AND qr_code_url != ''"),
    ]
    json_updates: list[tuple[str, str, str]] = [
        ("ai_result_resources", "content_json", "content_json IS NOT NULL"),
        ("ai_task_inputs", "value_json", "value_json IS NOT NULL"),
        ("agent_messages", "content_json", "content_json IS NOT NULL"),
        ("agent_tool_calls", "result_json", "result_json IS NOT NULL"),
    ]

    for table, column, where in text_updates:
        sql = (
            f"UPDATE {table} SET {column} = {_replace_expr(column)} "
            f"WHERE {where} AND {_needs_migration_predicate(column)};"
        )
        if dry_run:
            print(f"[dry-run] SQL: {sql[:200]}...")
            continue
        _mysql(sql)

    for table, column, where in json_updates:
        cast_col = f"CAST({column} AS CHAR)"
        sql = (
            f"UPDATE {table} SET {column} = CAST({_replace_expr(cast_col)} AS JSON) "
            f"WHERE {where} AND {_needs_migration_predicate(cast_col)};"
        )
        if dry_run:
            print(f"[dry-run] SQL: {sql[:200]}...")
            continue
        _mysql(sql)

    # asset storage metadata in system_settings
    meta_updates = {
        "assetStorage.provider": "oss",
        "assetStorage.publicBaseUrl": PROD_BASE,
        "assetStorage.ossEndpoint": "oss-cn-guangzhou.aliyuncs.com",
        "assetStorage.ossBucketProd": PROD_BUCKET,
        "assetStorage.note": "Production uses OSS prod bucket only. Dev uses local /generated. OSS AK in .env.",
        "assetStorage.switchGuide": (
            "Dev: ASSET_STORAGE_PROVIDER=local ASSET_STORAGE_PUBLIC_BASE_URL=/generated. "
            "Prod: ASSET_STORAGE_PROVIDER=oss ASSET_STORAGE_PUBLIC_BASE_URL="
            f"{PROD_BASE} OSS_BUCKET={PROD_BUCKET}. Restart backend+worker after change."
        ),
    }
    for key, value in meta_updates.items():
        sql = (
            f"UPDATE system_settings SET setting_value = '{_sql_escape(value)}' "
            f"WHERE setting_key = '{_sql_escape(key)}';"
        )
        if dry_run:
            print(f"[dry-run] {sql}")
        else:
            _mysql(sql)

    if not dry_run:
        # remove deprecated dev bucket key if present
        _mysql("DELETE FROM system_settings WHERE setting_key = 'assetStorage.ossBucketDev';")


def _mysql(sql: str) -> None:
    cmd = [
        "docker", "exec", MYSQL_CONTAINER,
        "mysql", f"-uroot", f"-p{MYSQL_PASSWORD}", MYSQL_DB,
        "-e", sql,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"MySQL failed: {result.stderr or result.stdout}")


def patch_env_file(env_path: Path, *, dry_run: bool) -> None:
    if not env_path.is_file():
        print(f"env file not found: {env_path}")
        return
    content = env_path.read_text(encoding="utf-8", errors="replace")
    replacements = {
        "ASSET_STORAGE_PROVIDER": "oss",
        "ASSET_STORAGE_PUBLIC_BASE_URL": PROD_BASE,
        "OSS_ENDPOINT": "oss-cn-guangzhou.aliyuncs.com",
        "OSS_BUCKET": PROD_BUCKET,
        "OSS_KEY_PREFIX": "",
    }
    for key, value in replacements.items():
        if re.search(rf"^{re.escape(key)}=.*$", content, flags=re.MULTILINE):
            content = re.sub(rf"^{re.escape(key)}=.*$", f"{key}={value}", content, flags=re.MULTILINE)
        else:
            content += f"\n{key}={value}\n"
    # strip dev bucket comments if any
    content = content.replace("wlcloudai-assets-dev", PROD_BUCKET)
    if dry_run:
        print(f"[dry-run] would patch {env_path}")
        return
    env_path.write_text(content, encoding="utf-8")
    print(f"patched {env_path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-oss-copy", action="store_true")
    parser.add_argument("--skip-local-upload", action="store_true")
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--skip-env", action="store_true")
    parser.add_argument("--env-path", default=str(ROOT / ".env"))
    args = parser.parse_args()

    print("=== migrate assets to prod bucket ===")
    print(f"dev={DEV_BUCKET} prod={PROD_BUCKET} local={LOCAL_MEDIA_DIR}")

    if not args.skip_oss_copy:
        copied, skipped = copy_dev_bucket_to_prod(dry_run=args.dry_run)
        print(f"oss copy: copied={copied} skipped_existing={skipped}")

    if not args.skip_local_upload:
        uploaded = upload_local_to_prod(dry_run=args.dry_run)
        print(f"local upload: {uploaded} files")

    if not args.skip_db:
        update_database(dry_run=args.dry_run)
        print("database URLs updated")

    if not args.skip_env:
        patch_env_file(Path(args.env_path), dry_run=args.dry_run)

    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
