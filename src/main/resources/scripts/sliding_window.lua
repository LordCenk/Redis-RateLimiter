local key = KEYS[1]
local now_ms = tonumber(ARGV[1])
local window_seconds = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])
local unique_id = ARGV[4]

local window_ms = window_seconds * 1000
local clear_before = now_ms - window_ms

-- 1. Remove timestamps outside the active sliding window
redis.call('ZREMRANGEBYSCORE', key, '-inf', clear_before)

-- 2. Count requests in active window
local current_count = redis.call('ZCARD', key)

local allowed = 0
local remaining = 0
local retry_after = 0

if current_count < max_requests then
    allowed = 1
    local member = tostring(now_ms) .. '-' .. tostring(unique_id)
    redis.call('ZADD', key, now_ms, member)
    remaining = max_requests - (current_count + 1)
    retry_after = 0
else
    allowed = 0
    remaining = 0
    -- Find oldest request timestamp in current window (index 0 in sorted set)
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    if #oldest >= 2 then
        local oldest_ts = tonumber(oldest[2])
        local expires_at = oldest_ts + window_ms
        local wait_ms = expires_at - now_ms
        if wait_ms > 0 then
            retry_after = math.ceil(wait_ms / 1000.0)
        else
            retry_after = 1
        end
    else
        retry_after = window_seconds
    end
    if retry_after <= 0 then
        retry_after = 1
    end
end

-- Refresh key expiration
local ttl = window_seconds * 2 + 10
redis.call('EXPIRE', key, ttl)

return { allowed, remaining, retry_after }
