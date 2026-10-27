#!/usr/bin/env bash
# Rate-Limited API Gateway Demo Script
# Sends 20 requests to GET /api/test and displays HTTP status and Retry-After header.

URL="${1:-http://localhost:8080/api/test}"
API_KEY="${2:-test-api-key-1}"
TOTAL=20

echo "======================================================================"
echo "RATE-LIMITED API GATEWAY - 20-REQUEST LIVE DEMONSTRATION"
echo "Target URL: $URL"
echo "API Key:    $API_KEY"
echo "======================================================================"

for i in $(seq 1 $TOTAL); do
  num=$(printf "%02d" "$i")
  response=$(curl -s -i -H "X-API-KEY: $API_KEY" "$URL")
  status=$(echo "$response" | grep -m 1 "HTTP/" | awk '{print $2}')
  retry_after=$(echo "$response" | grep -i "^retry-after:" | awk '{print $2}' | tr -d '\r')
  body=$(echo "$response" | awk 'BEGIN{RS="\r\n\r\n"} NR>1 {print $0; exit}')

  if [ "$status" = "200" ]; then
    echo "[Request $num] HTTP 200 OK | Response: $body"
  elif [ "$status" = "429" ]; then
    echo "[Request $num] HTTP 429 Too Many Requests | Retry-After: ${retry_after}s | Response: $body"
  else
    echo "[Request $num] HTTP $status | Response: $body"
  fi
  sleep 0.05
done

echo "======================================================================"
