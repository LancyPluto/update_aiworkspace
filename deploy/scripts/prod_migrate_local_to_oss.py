#!/usr/bin/env python3
"""Migrate production local files to OSS dual buckets (public + private).

Uploads local files from /data/generated-media/ to the appropriate OSS bucket,
then updates database URLs to point to new locations.

Run with --apply to execute. Default is dry-run.
"""
import mimetypes
import os
import subprocess
import sys
from pathlib import Path

try:
    import oss2
except ImportError:
    print("oss2 required: pip3 install oss2", file=sys.stderr)
    sys.exit(1)

MEDIA_ROOT = Path("/root/ai_tool_market/data/generated-media")
ENDPOINT = "https://oss-cn-guangzhou.aliyuncs.com"
PUBLIC_BUCKET = "wlcloudai-assets-public"
PRIVATE_BUCKET = "wlcloudai-assets-private"
PUBLIC_BASE_URL = f"https://{PUBLIC_BUCKET}.oss-cn-guangzhou.aliyuncs.com"
PRIVATE_BASE_URL = "/api/v1/assets/private"
AK = os.environ.get("OSS_ACCESS_KEY_ID") or os.environ.get("ALIYUN_CAPTCHA_ACCESS_KEY_ID", "")
SK = os.environ.get("OSS_ACCESS_KEY_SECRET") or os.environ.get("ALIYUN_CAPTCHA_ACCESS_KEY_SECRET", "")
DB = "ai_supermarket_v1"

PUBLIC_PREFIXES = ("tool-covers/", "customer-service/")


def mysql(sql):
    cmd = f'docker exec -i ai-supermarket-mysql mysql -uroot -proot123456 {DB} --batch --raw --skip-column-names -e "{sql}"'
    return subprocess.check_output(cmd, shell=True, text=True, encoding="utf-8", errors="replace").strip()


def bucket_client(name):
    auth = oss2.Auth(AK.strip(), SK.strip())
    return oss2.Bucket(auth, ENDPOINT, name)


def target_bucket(relative_key):
    if relative_key.startswith(PUBLIC_PREFIXES):
        return PUBLIC_BUCKET, f"{PUBLIC_BASE_URL}/{relative_key}"
    return PRIVATE_BUCKET, f"{PRIVATE_BASE_URL}/{relative_key}"


def upload_files(apply=False):
    if not MEDIA_ROOT.exists():
        print(f"media root not found: {MEDIA_ROOT}")
        return 0
    pub = bucket_client(PUBLIC_BUCKET)
    priv = bucket_client(PRIVATE_BUCKET)
    clients = {PUBLIC_BUCKET: pub, PRIVATE_BUCKET: priv}
    count = 0
    errors = 0
    for path in sorted(MEDIA_ROOT.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(MEDIA_ROOT).as_posix()
        bucket_name, _ = target_bucket(relative)
        ct = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        label = "upload" if apply else "dry-run"
        print(f"  {label} {relative} -> oss://{bucket_name}/{relative} ({ct})")
        if apply:
            try:
                data = path.read_bytes()
                headers = {"Content-Type": ct}
                clients[bucket_name].put_object(relative, data, headers=headers)
                count += 1
            except Exception as e:
                print(f"  ERROR uploading {relative}: {e}")
                errors += 1
        else:
            count += 1
    print(f"files: {count} uploaded, {errors} errors")
    return count


def update_database(apply=False):
    sqls = [
        # 1. Update system_settings dual-bucket config
        ("UPDATE system_settings SET setting_value='oss' WHERE setting_key='assetStorage.provider'", "assetStorage.provider -> oss"),
        (f"UPDATE system_settings SET setting_value='{PUBLIC_BASE_URL}' WHERE setting_key='assetStorage.publicBaseUrl'", "publicBaseUrl -> public bucket"),
        (f"INSERT INTO system_settings (setting_key, setting_value, setting_group, description) VALUES ('assetStorage.privateBaseUrl', '{PRIVATE_BASE_URL}', 'asset_storage', 'signed URL proxy') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", "add privateBaseUrl"),
        (f"INSERT INTO system_settings (setting_key, setting_value, setting_group, description) VALUES ('assetStorage.ossPublicBucket', '{PUBLIC_BUCKET}', 'asset_storage', 'public OSS bucket') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", "add ossPublicBucket"),
        (f"INSERT INTO system_settings (setting_key, setting_value, setting_group, description) VALUES ('assetStorage.ossPrivateBucket', '{PRIVATE_BUCKET}', 'asset_storage', 'private OSS bucket') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", "add ossPrivateBucket"),
        (f"INSERT INTO system_settings (setting_key, setting_value, setting_group, description) VALUES ('assetStorage.ossLegacyBucket', 'wlcloudai-assets-prod', 'asset_storage', 'legacy bucket') ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value)", "add ossLegacyBucket"),
        # 2. Tool covers: /generated/tool-covers/... -> public bucket URL
        (f"UPDATE ai_tools SET cover_url = CONCAT('{PUBLIC_BASE_URL}/', SUBSTRING(cover_url, LENGTH('/generated/') + 1)) WHERE cover_url LIKE '/generated/tool-covers/%'", "tool covers -> public bucket"),
        # 3. Avatars: /generated/avatars/... -> private proxy
        (f"UPDATE users SET avatar_url = CONCAT('{PRIVATE_BASE_URL}/', SUBSTRING(avatar_url, LENGTH('/generated/') + 1)) WHERE avatar_url LIKE '/generated/avatars/%'", "avatars -> private proxy"),
        # 4. Result resources: /generated/... -> private proxy (images, video, audio etc)
        (f"UPDATE ai_result_resources SET content_text = REPLACE(content_text, '/generated/', '{PRIVATE_BASE_URL}/') WHERE content_text LIKE '%/generated/%'", "result resources -> private proxy"),
        # 5. Record migration
        ("INSERT IGNORE INTO _sql_migration_log (name) VALUES ('prod_migrate_local_to_oss_dual_bucket.sql')", "record migration"),
    ]
    for sql, desc in sqls:
        label = "sql" if apply else "dry-run"
        print(f"  {label}: {desc}")
        if apply:
            try:
                mysql(sql)
            except Exception as e:
                print(f"  ERROR: {e}")


def update_env(apply=False):
    env_path = Path("/root/ai_tool_market/.env")
    if not env_path.exists():
        print("  .env not found")
        return
    content = env_path.read_text(encoding="utf-8", errors="replace")
    import re
    # Remove old OSS-related lines
    keys_to_remove = [
        "ASSET_STORAGE_PROVIDER", "ASSET_STORAGE_PUBLIC_BASE_URL",
        "ASSET_STORAGE_PRIVATE_BASE_URL", "OSS_ENDPOINT", "OSS_BUCKET",
        "OSS_PUBLIC_BUCKET", "OSS_PRIVATE_BUCKET", "OSS_LEGACY_BUCKET", "OSS_KEY_PREFIX",
    ]
    for key in keys_to_remove:
        content = re.sub(rf"^{re.escape(key)}=.*\n?", "", content, flags=re.MULTILINE)
    # Remove old comment block about OSS
    content = re.sub(r"^# .*ASSET_STORAGE_PROVIDER.*\n?", "", content, flags=re.MULTILINE)
    content = re.sub(r"^# .*ASSET_STORAGE_PUBLIC_BASE_URL.*\n?", "", content, flags=re.MULTILINE)
    if not content.endswith("\n"):
        content += "\n"
    block = f"""
# OSS dual-bucket storage (public + private, signed URL proxy for private assets)
ASSET_STORAGE_PROVIDER=oss
ASSET_STORAGE_PUBLIC_BASE_URL={PUBLIC_BASE_URL}
ASSET_STORAGE_PRIVATE_BASE_URL={PRIVATE_BASE_URL}
OSS_ENDPOINT=oss-cn-guangzhou.aliyuncs.com
OSS_PUBLIC_BUCKET={PUBLIC_BUCKET}
OSS_PRIVATE_BUCKET={PRIVATE_BUCKET}
OSS_LEGACY_BUCKET=wlcloudai-assets-prod
OSS_KEY_PREFIX=
""".strip()
    content += "\n" + block + "\n"
    if apply:
        env_path.write_text(content, encoding="utf-8")
        print("  .env updated with OSS dual-bucket config")
    else:
        print("  dry-run: would update .env with OSS dual-bucket config")


def main():
    apply = "--apply" in sys.argv
    if not AK or not SK:
        print("ERROR: OSS_ACCESS_KEY_ID/SECRET or ALIYUN_CAPTCHA_ACCESS_KEY_ID/SECRET required")
        return 1
    print(f"=== Mode: {'APPLY' if apply else 'DRY-RUN'} ===\n")

    print("Step 1: Upload local files to OSS")
    upload_files(apply=apply)

    print("\nStep 2: Update database URLs")
    update_database(apply=apply)

    print("\nStep 3: Update .env")
    update_env(apply=apply)

    if not apply:
        print("\n*** Dry-run complete. Rerun with --apply to execute. ***")
    else:
        print("\n*** Migration complete. Restart containers: ***")
        print("  cd /root/ai_tool_market/deploy")
        print("  docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend worker")
        print("  docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend worker")
    return 0


if __name__ == "__main__":
    sys.exit(main())
