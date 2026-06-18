#!/usr/bin/env python3
"""Migrate wlcloudai-assets-prod bucket objects to public/private buckets, then delete from prod.

Usage:
  python3 prod_migrate_legacy_bucket.py          # dry-run: list what would happen
  python3 prod_migrate_legacy_bucket.py --apply   # copy + update DB + delete source
"""
import os
import subprocess
import sys

try:
    import oss2
except ImportError:
    print("oss2 required: pip3 install oss2", file=sys.stderr)
    sys.exit(1)

ENDPOINT = "https://oss-cn-guangzhou.aliyuncs.com"
PROD_BUCKET = "wlcloudai-assets-prod"
PUBLIC_BUCKET = "wlcloudai-assets-public"
PRIVATE_BUCKET = "wlcloudai-assets-private"
PUBLIC_BASE = f"https://{PUBLIC_BUCKET}.oss-cn-guangzhou.aliyuncs.com"
PRIVATE_BASE = "/api/v1/assets/private"
PROD_BASE = f"https://{PROD_BUCKET}.oss-cn-guangzhou.aliyuncs.com"
DB = "ai_supermarket_v1"

AK = (os.environ.get("OSS_ACCESS_KEY_ID") or os.environ.get("ALIYUN_CAPTCHA_ACCESS_KEY_ID") or "").strip()
SK = (os.environ.get("OSS_ACCESS_KEY_SECRET") or os.environ.get("ALIYUN_CAPTCHA_ACCESS_KEY_SECRET") or "").strip()

PUBLIC_PREFIXES = ("tool-covers/", "customer-service/", "market-files/", "icons/")


def bucket_client(name):
    return oss2.Bucket(oss2.Auth(AK, SK), ENDPOINT, name)


def target_for_key(key):
    if key.startswith(PUBLIC_PREFIXES):
        return PUBLIC_BUCKET, f"{PUBLIC_BASE}/{key}"
    return PRIVATE_BUCKET, f"{PRIVATE_BASE}/{key}"


def mysql(sql):
    cmd = f'docker exec -i ai-supermarket-mysql mysql -uroot -proot123456 {DB} --batch --raw --skip-column-names -e "{sql}"'
    return subprocess.check_output(cmd, shell=True, text=True, encoding="utf-8", errors="replace").strip()


def main():
    apply = "--apply" in sys.argv
    if not AK or not SK:
        print("ERROR: need OSS_ACCESS_KEY_ID/SECRET or ALIYUN_CAPTCHA_ACCESS_KEY_ID/SECRET")
        return 1

    prod = bucket_client(PROD_BUCKET)
    pub = bucket_client(PUBLIC_BUCKET)
    priv = bucket_client(PRIVATE_BUCKET)
    targets = {PUBLIC_BUCKET: pub, PRIVATE_BUCKET: priv}

    print(f"=== Mode: {'APPLY' if apply else 'DRY-RUN'} ===\n")

    # Step 1: List and copy objects
    print("Step 1: Copy objects from prod bucket to public/private buckets")
    objects = []
    by_prefix = {}
    for obj in oss2.ObjectIterator(prod):
        objects.append(obj.key)
        prefix = obj.key.split("/")[0] if "/" in obj.key else "(root)"
        by_prefix[prefix] = by_prefix.get(prefix, 0) + 1

    print(f"  Found {len(objects)} objects in oss://{PROD_BUCKET}")
    for p in sorted(by_prefix):
        print(f"    {p}/: {by_prefix[p]}")

    copied = 0
    errors = 0
    for key in objects:
        dest_bucket, _ = target_for_key(key)
        label = "copy" if apply else "dry-run"
        print(f"  {label}: oss://{PROD_BUCKET}/{key} -> oss://{dest_bucket}/{key}")
        if apply:
            try:
                targets[dest_bucket].copy_object(PROD_BUCKET, key, key)
                copied += 1
            except Exception as e:
                print(f"  ERROR copying {key}: {e}")
                errors += 1
    print(f"  Copied: {copied}, Errors: {errors}\n")

    # Step 2: Update database URLs still pointing to prod bucket
    print("Step 2: Update database URLs referencing prod bucket")
    db_updates = [
        (f"UPDATE ai_tools SET cover_url = REPLACE(cover_url, '{PROD_BASE}/', '{PUBLIC_BASE}/') WHERE cover_url LIKE '{PROD_BASE}/%'",
         "ai_tools.cover_url -> public bucket"),
        (f"UPDATE users SET avatar_url = CONCAT('{PRIVATE_BASE}/', SUBSTRING_INDEX(avatar_url, '.aliyuncs.com/', -1)) WHERE avatar_url LIKE '%{PROD_BUCKET}%'",
         "users.avatar_url -> private proxy"),
        (f"UPDATE ai_result_resources SET content_text = REPLACE(content_text, '{PROD_BASE}/', '{PRIVATE_BASE}/') WHERE content_text LIKE '%{PROD_BASE}/%'",
         "ai_result_resources -> private proxy"),
        (f"UPDATE system_settings SET setting_value = REPLACE(setting_value, '{PROD_BASE}/', '{PUBLIC_BASE}/') WHERE setting_value LIKE '%{PROD_BASE}/%' AND setting_key NOT IN ('assetStorage.ossBucketProd','assetStorage.ossLegacyBucket','assetStorage.switchGuide','assetStorage.note')",
         "system_settings URLs -> public bucket"),
        (f"UPDATE community_posts SET cover_url = REPLACE(cover_url, '{PROD_BASE}/', '{PRIVATE_BASE}/') WHERE cover_url LIKE '%{PROD_BASE}/%'",
         "community_posts.cover_url -> private proxy"),
        (f"UPDATE community_posts SET media_url = REPLACE(media_url, '{PROD_BASE}/', '{PRIVATE_BASE}/') WHERE media_url LIKE '%{PROD_BASE}/%'",
         "community_posts.media_url -> private proxy"),
    ]
    for sql, desc in db_updates:
        label = "sql" if apply else "dry-run"
        print(f"  {label}: {desc}")
        if apply:
            try:
                mysql(sql)
            except Exception as e:
                print(f"  ERROR: {e}")
    print()

    # Step 3: Delete all objects from prod bucket
    print("Step 3: Delete objects from prod bucket")
    deleted = 0
    if apply:
        for key in objects:
            try:
                prod.delete_object(key)
                deleted += 1
            except Exception as e:
                print(f"  ERROR deleting {key}: {e}")
        print(f"  Deleted {deleted}/{len(objects)} objects from oss://{PROD_BUCKET}")
    else:
        print(f"  dry-run: would delete {len(objects)} objects from oss://{PROD_BUCKET}")

    # Step 4: Verify prod bucket is empty
    if apply:
        remaining = sum(1 for _ in oss2.ObjectIterator(prod))
        print(f"\n  Remaining objects in prod bucket: {remaining}")
        if remaining == 0:
            print("  prod bucket is EMPTY")

    if not apply:
        print("\n*** Dry-run complete. Rerun with --apply to execute. ***")
    else:
        print("\n*** Legacy bucket migration complete. ***")
    return 0


if __name__ == "__main__":
    sys.exit(main())
