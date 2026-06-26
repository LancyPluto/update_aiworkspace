#!/usr/bin/env python3
"""Upload vendor icons to OSS bucket"""
import os
import sys

try:
    import oss2
except ImportError:
    print("Installing oss2...")
    os.system("pip install oss2 -q")
    import oss2

# OSS配置
ENDPOINT = "oss-cn-guangzhou.aliyuncs.com"
ACCESS_KEY_ID = "LTAI5tAVnSWHAAHnHhDuuk2y"
ACCESS_KEY_SECRET = "bDpR3vwAWYiF8vIYoRFKuzUwM4jWnW"
BUCKET_NAME = "wlcloudai-assets-public"
PREFIX = "vendor-icons/"

# 本地目录
LOCAL_DIR = "D:/download/test/ai-tool-market/admin-frontend/public/assets/vendor-icons"

def upload_vendor_icons():
    # 创建OSS客户端
    auth = oss2.Auth(ACCESS_KEY_ID, ACCESS_KEY_SECRET)
    bucket = oss2.Bucket(auth, ENDPOINT, BUCKET_NAME)

    # 遍历本地目录
    uploaded = 0
    failed = 0

    for filename in os.listdir(LOCAL_DIR):
        if not filename.endswith('.svg'):
            continue

        local_path = os.path.join(LOCAL_DIR, filename)
        oss_key = PREFIX + filename

        try:
            # 上传文件，设置为公共读
            headers = {
                'Content-Type': 'image/svg+xml',
                'x-oss-object-acl': 'public-read'
            }
            with open(local_path, 'rb') as f:
                bucket.put_object(oss_key, f, headers=headers)

            print(f"[OK] Uploaded: {filename}")
            uploaded += 1
        except Exception as e:
            print(f"[FAIL] Failed: {filename} - {e}")
            failed += 1

    print(f"\nCompleted: {uploaded} uploaded, {failed} failed")
    return failed == 0

if __name__ == "__main__":
    success = upload_vendor_icons()
    sys.exit(0 if success else 1)
