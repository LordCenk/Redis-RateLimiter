package com.example.gateway.limiter;

public interface RateLimiter {

    /**
     * Attempts to acquire allowance for a request under the specified rate-limit identity.
     *
     * @param identity the rate-limit identity (e.g. API key, username, or IP)
     * @return RateLimitResult indicating allowed status, remaining allowance, and retry-after seconds
     */
    RateLimitResult tryAcquire(String identity);
}
