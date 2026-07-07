#!/usr/bin/env python3
"""Test OFOX gpt-image-2 API balance status with real credentials."""

import requests
import json
import sys

# Real OFOX API credentials from database (vendor_account_id=47)
OFOX_API_KEY = "sk-of-lhWOVDJUMxMTIVCopopUSrMkGrMCGzabsDkfXqWxjnLdiEwamXJSzPUhAjpumLAX"
OFOX_BASE_URL = "https://api.ofox.ai/v1"

print("=" * 70)
print("Testing OFOX GPT-Image-2 API Balance Status")
print("=" * 70)
print(f"Base URL: {OFOX_BASE_URL}")
print(f"API Key: sk-of-lhWO...{OFOX_API_KEY[-8:]}")
print(f"Database Record: vendor_account_id=47, balance=10.00 CNY, status=OK")
print("=" * 70)

# Test 1: Try to generate an image
print("\n[Test] Attempting minimal image generation request...")
print("-" * 70)

try:
    response = requests.post(
        f"{OFOX_BASE_URL}/images/generations",
        headers={
            "Authorization": f"Bearer {OFOX_API_KEY}",
            "Content-Type": "application/json",
        },
        json={
            "model": "openai/gpt-image-2",
            "prompt": "a simple test image",
            "size": "1024x1024",
            "n": 1,
        },
        timeout=30
    )
    
    print(f"\nHTTP Status Code: {response.status_code}")
    print(f"Response Headers:")
    for key, value in response.headers.items():
        if key.lower() in ['content-type', 'x-ratelimit-limit', 'x-ratelimit-remaining']:
            print(f"  {key}: {value}")
    
    try:
        body = response.json()
        print(f"\nResponse Body (JSON):")
        print(json.dumps(body, indent=2, ensure_ascii=False))
        
        # Check for error details
        if 'error' in body:
            error = body['error']
            print(f"\n❌ ERROR DETECTED:")
            print(f"   Code: {error.get('code', 'N/A')}")
            print(f"   Message: {error.get('message', 'N/A')}")
            print(f"   Type: {error.get('type', 'N/A')}")
            
            # Check for insufficient credits
            if response.status_code == 402 or 'Insufficient credits' in str(error.get('message', '')):
                print("\n" + "=" * 70)
                print("⚠️  CONFIRMED: Account balance is INSUFFICIENT!")
                print("=" * 70)
                print("\nDespite database showing balance=10.00 CNY and status=OK,")
                print("the OFOX API gateway is returning 402 Payment Required.")
                print("\nThis means:")
                print("  1. The local database balance is out of sync with OFOX")
                print("  2. OFOX's internal balance is actually negative or zero")
                print("  3. Need to recharge the OFOX account directly")
                sys.exit(1)
            elif 'safety system' in str(error.get('message', '')).lower():
                print("\n⚠️  Request rejected by safety system (not a balance issue)")
            else:
                print(f"\n️  Other error occurred")
                
    except json.JSONDecodeError:
        print(f"\nResponse Body (Text - first 500 chars):")
        print(response.text[:500])
    
    # Final verdict based on status code
    if response.status_code == 402:
        print("\n" + "=" * 70)
        print("️  CONFIRMED: Account balance is INSUFFICIENT (HTTP 402)")
        print("=" * 70)
        print("\nThe OFOX API returned HTTP 402 Payment Required.")
        print("This confirms that the OFOX account needs to be recharged.")
        sys.exit(1)
    elif response.status_code == 401:
        print("\n" + "=" * 70)
        print("❌ ERROR: Invalid API Key (HTTP 401)")
        print("=" * 70)
        sys.exit(1)
    elif response.status_code == 400:
        print("\n" + "=" * 70)
        print("️  Request rejected (HTTP 400)")
        print("=" * 70)
        print("This could be due to:")
        print("  - Safety filter rejection")
        print("  - Invalid parameters")
        print("  - Model not available")
        print("\nBut NOT necessarily a balance issue.")
    elif response.status_code == 200:
        print("\n" + "=" * 70)
        print("✅ SUCCESS: API call succeeded!")
        print("=" * 70)
        print("\nThe OFOX account has sufficient balance.")
        print("If tasks are still failing, check other factors:")
        print("  - Network connectivity")
        print("  - Timeout settings")
        print("  - Worker queue processing")
        sys.exit(0)
    else:
        print(f"\n⚠️  Unexpected status code: {response.status_code}")
        
except requests.exceptions.Timeout:
    print("\n❌ ERROR: Request timed out after 30 seconds")
    print("This could indicate network issues or OFOX server problems.")
    sys.exit(1)
except requests.exceptions.ConnectionError as e:
    print(f"\n❌ ERROR: Connection failed: {e}")
    print("Cannot reach OFOX API server.")
    sys.exit(1)
except Exception as e:
    print(f"\n❌ ERROR: Unexpected exception: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()
    sys.exit(1)

print("\n" + "=" * 70)
print("Test completed")
print("=" * 70)
