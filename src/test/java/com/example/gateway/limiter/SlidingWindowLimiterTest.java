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
class SlidingWindowLimiterTest {

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        RedisTestSupport.configureRedis(registry);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @SuppressWarnings("rawtypes")
    private RedisScript<List> slidingWindowScript;

    private TestClock testClock;
    private RateLimitProperties properties;
    private SlidingWindowLimiter limiter;
    private String testIdentityA;
    private String testIdentityB;

    @BeforeEach
    void setUp() {
        testClock = new TestClock(Instant.ofEpochMilli(1_000_000_000L));
        properties = new RateLimitProperties();
        properties.setWindowSeconds(10);
        properties.setMaxRequests(3);

        limiter = new SlidingWindowLimiter(redisTemplate, slidingWindowScript, properties, testClock);

        testIdentityA = "test-sw-user-" + UUID.randomUUID();
        testIdentityB = "test-sw-user-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete("rate_limit:sliding_window:" + testIdentityA);
        redisTemplate.delete("rate_limit:sliding_window:" + testIdentityB);
    }

    @Test
    @DisplayName("4. Sliding window allows requests below its configured limit")
    void testSlidingWindowAllowsRequestsBelowLimit() {
        RateLimitResult r1 = limiter.tryAcquire(testIdentityA);
        assertTrue(r1.allowed(), "First request should be allowed");
        assertEquals(2, r1.remainingAllowance());

        RateLimitResult r2 = limiter.tryAcquire(testIdentityA);
        assertTrue(r2.allowed(), "Second request should be allowed");
        assertEquals(1, r2.remainingAllowance());

        RateLimitResult r3 = limiter.tryAcquire(testIdentityA);
        assertTrue(r3.allowed(), "Third request should be allowed");
        assertEquals(0, r3.remainingAllowance());
    }

    @Test
    @DisplayName("5. Sliding window rejects requests at the limit")
    void testSlidingWindowRejectsAtLimit() {
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);

        RateLimitResult r4 = limiter.tryAcquire(testIdentityA);
        assertFalse(r4.allowed(), "Request should be rejected once max requests is reached");
        assertEquals(0, r4.remainingAllowance());
        assertTrue(r4.retryAfterSeconds() >= 1 && r4.retryAfterSeconds() <= 10,
                "Retry-After should be between 1 and window-seconds");
    }

    @Test
    @DisplayName("6. Sliding window allows requests again after old entries expire")
    void testSlidingWindowAllowsRequestsAfterExpiration() {
        limiter.tryAcquire(testIdentityA);

        testClock.advanceSeconds(2);
        limiter.tryAcquire(testIdentityA);

        testClock.advanceSeconds(2);
        limiter.tryAcquire(testIdentityA);

        assertFalse(limiter.tryAcquire(testIdentityA).allowed(), "Should be rejected at limit");

        // Advance clock past expiration of first request (t=0s, window=10s)
        testClock.advanceSeconds(7);

        RateLimitResult rAfterSlide = limiter.tryAcquire(testIdentityA);
        assertTrue(rAfterSlide.allowed(), "Request should be allowed after oldest request slid outside window");
    }

    @Test
    @DisplayName("7. Different API keys maintain independent limits (Sliding Window)")
    void testIndependentLimitsForDifferentKeys() {
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        limiter.tryAcquire(testIdentityA);
        assertFalse(limiter.tryAcquire(testIdentityA).allowed(), "Identity A should be exhausted");

        RateLimitResult bResult = limiter.tryAcquire(testIdentityB);
        assertTrue(bResult.allowed(), "Identity B should have its own separate window");
        assertEquals(2, bResult.remainingAllowance());
    }
}
