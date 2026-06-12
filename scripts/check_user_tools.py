#!/usr/bin/env python3
import json
import os
import sys
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
WORKFLOW_CODES = [
    "ai_comic_drama_agent",
    "enterprise_diagnosis_agent",
    "digital_human_agent",
    "banana_ppt_generator",
    "social_media_comment_insights_agent",
]

req = urllib.request.Request(f"{BASE}/api/v1/tools?pageNo=1&pageSize=200")
with urllib.request.urlopen(req, timeout=30) as resp:
    data = json.load(resp)["data"]

print(f"base={BASE} total={data['total']}")
for code in WORKFLOW_CODES:
    hit = next((t for t in data["list"] if t["toolCode"] == code), None)
    if hit:
        print(f"  {code}: ONLINE status={hit.get('status')} toolType={hit.get('toolType')} category={hit.get('categoryName')}")
    else:
        print(f"  {code}: NOT_IN_USER_LIST")
