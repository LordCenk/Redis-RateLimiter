package com.example.gateway.limiter;

public record RateLimitResult(
        boolean allowed,
        long remainingAllowance,
        long retryAfterSeconds
) {
    public static RateLimitResult allowed(long remainingAllowance) {
        return new RateLimitResult(true, remainingAllowance, 0L);
    }

    public static RateLimitResult rejected(long retryAfterSeconds) {
        return new RateLimitResult(false, 0L, Math.max(1L, retryAfterSeconds));
    }
}
