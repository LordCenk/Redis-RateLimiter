local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local requested = tonumber(ARGV[4]) or 1

-- Fetch existing state from Redis Hash
local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(data[1])
local last_refill = tonumber(data[2])

if tokens == nil or last_refill == nil then
    tokens = capacity
    last_refill = now_ms
else
    local elapsed_ms = math.max(0, now_ms - last_refill)
    local elapsed_seconds = elapsed_ms / 1000.0
    tokens = math.min(capacity, tokens + (elapsed_seconds * refill_rate))
    last_refill = now_ms
end

local allowed = 0
local retry_after = 0
local remaining = 0

if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
    remaining = math.floor(tokens)
    retry_after = 0
else
    allowed = 0
    remaining = math.floor(tokens)
    local deficit = requested - tokens
    retry_after = math.ceil(deficit / refill_rate)
    if retry_after <= 0 then
        retry_after = 1
    end
end

-- Save state
redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)

-- Set key expiration so obsolete state is cleaned up
local ttl = math.ceil(capacity / refill_rate) * 2 + 60
redis.call('EXPIRE', key, ttl)

return { allowed, remaining, retry_after }
