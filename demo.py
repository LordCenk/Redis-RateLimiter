#!/usr/bin/env python3
"""
Rate-Limited API Gateway Demo Script
Sends 20 requests to GET /api/test and displays HTTP status and Retry-After header.
Uses only the Python standard library.
"""

import sys
import time
import urllib.request
import urllib.error

DEFAULT_URL = "http://127.0.0.1:8080/api/test"
DEFAULT_API_KEY = "test-api-key-1"
TOTAL_REQUESTS = 20

def run_demo(url=DEFAULT_URL, api_key=DEFAULT_API_KEY, total=TOTAL_REQUESTS, delay=0.05):
    print("=" * 70, flush=True)
    print("RATE-LIMITED API GATEWAY - 20-REQUEST LIVE DEMONSTRATION", flush=True)
    print(f"Target URL: {url}", flush=True)
    print(f"API Key:    {api_key}", flush=True)
    print(f"Requests:   {total}", flush=True)
    print("=" * 70, flush=True)

    success_count = 0
    throttled_count = 0
    other_count = 0

    for i in range(1, total + 1):
        req = urllib.request.Request(
            url,
            headers={"X-API-KEY": api_key, "User-Agent": "RateLimitDemo/1.0"}
        )
        try:
            with urllib.request.urlopen(req) as resp:
                status = resp.status
                body = resp.read().decode('utf-8')
                success_count += 1
                print(f"[Request {i:02d}] HTTP {status} OK | Response: {body.strip()}", flush=True)
        except urllib.error.HTTPError as e:
            status = e.code
            retry_after = e.headers.get("Retry-After", "N/A")
            body = e.read().decode('utf-8') if e.fp else ""
            if status == 429:
                throttled_count += 1
                print(f"[Request {i:02d}] HTTP {status} Too Many Requests | Retry-After: {retry_after}s | Response: {body.strip()}", flush=True)
            else:
                other_count += 1
                print(f"[Request {i:02d}] HTTP {status} Error | Response: {body.strip()}", flush=True)
        except urllib.error.URLError as e:
            print(f"[Request {i:02d}] Connection Error: {e.reason}", flush=True)
            print("\nError: Could not connect to the API Gateway. Is Spring Boot running on port 8080?", flush=True)
            sys.exit(1)

        if delay > 0 and i < total:
            time.sleep(delay)

    print("=" * 70, flush=True)
    print("DEMONSTRATION SUMMARY:", flush=True)
    print(f"  Total Requests:     {total}", flush=True)
    print(f"  Allowed (200 OK):   {success_count}", flush=True)
    print(f"  Throttled (429):    {throttled_count}", flush=True)
    if other_count > 0:
        print(f"  Other Errors:       {other_count}", flush=True)
    print("=" * 70, flush=True)

if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_URL
    api_key = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_API_KEY
    run_demo(url=url, api_key=api_key)
