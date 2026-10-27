package com.example.gateway.limiter;

import com.example.gateway.RedisTestSupport;
import com.example.gateway.TestClock;
import com.example.gateway.config.RateLimitConfig.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TokenBucketLimiterTest {

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        RedisTestSupport.configureRedis(registry);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @SuppressWarnings("rawtypes")
    private RedisScript<List> tokenBucketScript;

    private TestClock testClock;
    private RateLimitProperties properties;
    private TokenBucketLimiter limiter;
    private String testIdentityA;
    private String testIdentityB;

    @BeforeEach
    void setUp() {
        testClock = new TestClock(Instant.ofEpochMilli(1_000_000_000L));
        properties = new RateLimitProperties();
        properties.setCapacity(3);
        properties.setRefillRate(1.0); // 1 token per second

        limiter = new TokenBucketLimiter(redisTemplate, tokenBucketScript, properties, testClock);

        testIdentityA = "test-tb-user-" + UUID.randomUUID();
        testIdentityB = "test-tb-user-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("rate_limit:token_bucket:" + testIdentityA);
        redisTemplate.delete("rate_limit:token_bucket:" + testIdentityB);
    }

    @Test
    @DisplayName("1. Token bucket allows requests while tokens are available")
    void testTokenBucketAllowsRequestsWhileTokensAvailable() {
        RateLimitResult r1 = limiter.tryAcquire(testIdentityA);
        assertTrue(r1.allowed(), "First request should be allowed");
        assertEquals(2, r1.remainingAllowance());
        assertEquals(0, r1.retryAfterSeconds());

        RateLimitResult r2 = limiter.tryAcquire(testIdentityA);
        assertTrue(r2.allowed(), "Second request should be allowed");
        assertEquals(1, r2.remainingAllowance());

        RateLimitResult r3 = limiter.tryAcquire(testIdentityA);
        assertTrue(r3.allowed(), "Third request should be allowed");
        assertEquals(0, r3.remainingAllowance());
    }

    @Test
    @DisplayName("2. Token bucket rejects requests when empty")
    void testTokenBucketRejectsWhenEmpty() {
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);

        RateLimitResult r4 = limiter.tryAcquire(testIdentityA);
        assertFalse(r4.allowed(), "Request should be rejected when bucket is empty");
        assertEquals(0, r4.remainingAllowance());
        assertTrue(r4.retryAfterSeconds() >= 1, "Retry-After should be at least 1 second");
    }

    @Test
    @DisplayName("3. Token bucket refills over time")
    void testTokenBucketRefillsOverTime() {
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        assertFalse(limiter.tryAcquire(testIdentityA).allowed());

        // Advance test clock by 2 seconds
        testClock.advanceSeconds(2);

        RateLimitResult refilled1 = limiter.tryAcquire(testIdentityA);
        assertTrue(refilled1.allowed(), "Request should be allowed after refilling tokens");

        RateLimitResult refilled2 = limiter.tryAcquire(testIdentityA);
        assertTrue(refilled2.allowed(), "Second refilled token should be allowed");

        RateLimitResult refilled3 = limiter.tryAcquire(testIdentityA);
        assertFalse(refilled3.allowed(), "Should be rejected again once refilled tokens are consumed");
    }

    @Test
    @DisplayName("7. Different API keys maintain independent limits (Token Bucket)")
    void testIndependentLimitsForDifferentKeys() {
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        assertFalse(limiter.tryAcquire(testIdentityA).allowed(), "Identity A should be exhausted");

        RateLimitResult bResult = limiter.tryAcquire(testIdentityB);
        assertTrue(bResult.allowed(), "Identity B should have its own separate capacity");
        assertEquals(2, bResult.remainingAllowance());
    }
}
