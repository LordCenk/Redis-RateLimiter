package com.example.gateway.limiter;

import com.example.gateway.config.RateLimitConfig.RateLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SlidingWindowLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rate_limit:sliding_window:";

    private final StringRedisTemplate redisTemplate;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;
    private final RateLimitProperties properties;
    private final Clock clock;

    public SlidingWindowLimiter(
            StringRedisTemplate redisTemplate,
            @SuppressWarnings("rawtypes") RedisScript<List> script,
            RateLimitProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.script = script;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String identity) {
        String key = KEY_PREFIX + identity;
        long nowMs = clock.millis();
        String uniqueId = UUID.randomUUID().toString();

        List<?> result = redisTemplate.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(nowMs),
                String.valueOf(properties.getWindowSeconds()),
                String.valueOf(properties.getMaxRequests()),
                uniqueId
        );

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("Invalid response from sliding window Redis script");
        }

        long allowed = ((Number) result.get(0)).longValue();
        long remaining = ((Number) result.get(1)).longValue();
        long retryAfter = ((Number) result.get(2)).longValue();

        if (allowed == 1L) {
            return RateLimitResult.allowed(remaining);
        } else {
            return RateLimitResult.rejected(retryAfter);
        }
    }
}
