#!/usr/bin/env python3
"""Test ofox gpt-image-2 API balance status."""

import os
import sys
import requests

# Load environment variables from .env file if exists
if os.path.exists('.env'):
    with open('.env', 'r') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                os.environ[key] = value

# OFOX API Key from database (vendor_account_id=47)
# This is the key used by gpt_image2 tool (model_config_id=22)
OFOX_API_KEY = os.environ.get('OFOX_API_KEY', 'sk-of-lhWO9XZvKqJ8mNpRtYwHx3CdEfGhIjKlMnOpQrStUvWxYz')
OFOX_BASE_URL = os.environ.get('OFOX_BASE_URL', 'https://api.ofox.ai/v1')

print("=" * 60)
print("Testing OFOX GPT-Image-2 API Balance")
print("=" * 60)
print(f"Base URL: {OFOX_BASE_URL}")
print(f"API Key: {'***' + OFOX_API_KEY[-4:] if len(OFOX_API_KEY) > 4 else '(not set)'}")
print("=" * 60)

if not OFOX_API_KEY or OFOX_API_KEY.startswith('replace-with'):
    print("\n❌ ERROR: OFOX_API_KEY is not configured!")
    print("Please set OFOX_API_KEY in your .env file")
    sys.exit(1)

# Test 1: Try to generate an image (minimal request)
print("\n[Test 1] Attempting minimal image generation request...")
print("-" * 60)

try:
    response = requests.post(
        f"{OFOX_BASE_URL}/images/generations",
        headers={
            "Authorization": f"Bearer {OFOX_API_KEY}",
            "Content-Type": "application/json",
        },
        json={
            "model": "gpt-image-2",
            "prompt": "test",
            "size": "1024x1024",
            "n": 1,
        },
        timeout=30
    )
    
    print(f"HTTP Status Code: {response.status_code}")
    print(f"Response Headers: {dict(response.headers)}")
    
    try:
        body = response.json()
        print(f"\nResponse Body (JSON):")
        import json
        print(json.dumps(body, indent=2, ensure_ascii=False))
    except:
        print(f"\nResponse Body (Text):")
        print(response.text[:500])
    
    # Check for specific error codes
    if response.status_code == 402:
        print("\n" + "=" * 60)
        print("⚠️  CONFIRMED: Account balance is INSUFFICIENT (HTTP 402)")
        print("=" * 60)
        sys.exit(1)
    elif response.status_code == 401:
        print("\n" + "=" * 60)
        print("❌ ERROR: Invalid API Key (HTTP 401)")
        print("=" * 60)
        sys.exit(1)
    elif response.status_code == 400:
        print("\n" + "=" * 60)
        print("⚠️  Request rejected (HTTP 400) - may be safety filter or invalid params")
        print("=" * 60)
    elif response.status_code == 200:
        print("\n" + "=" * 60)
        print("✅ SUCCESS: API call succeeded - account has sufficient balance!")
        print("=" * 60)
    else:
        print(f"\nUnexpected status code: {response.status_code}")
        
except requests.exceptions.Timeout:
    print("\n❌ ERROR: Request timed out")
    sys.exit(1)
except requests.exceptions.ConnectionError as e:
    print(f"\n ERROR: Connection failed: {e}")
    sys.exit(1)
except Exception as e:
    print(f"\n❌ ERROR: Unexpected exception: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()
    sys.exit(1)

print("\n" + "=" * 60)
print("Test completed")
print("=" * 60)
