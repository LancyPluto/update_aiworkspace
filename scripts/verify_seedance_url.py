#!/usr/bin/env python3
import sys
sys.path.insert(0, "d:/download/test/ai-tool-market/worker")
from client.seedance_video_client import SeedanceVideoClient

client = SeedanceVideoClient.from_model_config({
    "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
    "apiKey": "test-key",
    "modelName": "doubao-seedance-1-5-pro-251215",
})
print("base_url=", client.base_url)
print("create_path=", client.create_path)
assert client.create_path == "/contents/generations/tasks"
print("OK")
