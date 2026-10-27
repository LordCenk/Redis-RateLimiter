package com.example.gateway.config;

import com.example.gateway.limiter.RateLimiter;
import com.example.gateway.limiter.SlidingWindowLimiter;
import com.example.gateway.limiter.TokenBucketLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AlgorithmSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class, RateLimitConfig.class));

    @Test
    @DisplayName("11. Configured algorithm selects TokenBucketLimiter when TOKEN_BUCKET")
    void testTokenBucketSelected() {
        contextRunner
                .withPropertyValues("rate-limit.algorithm=TOKEN_BUCKET")
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    RateLimiter limiter = context.getBean(RateLimiter.class);
                    assertThat(limiter).isInstanceOf(TokenBucketLimiter.class);
                });
    }

    @Test
    @DisplayName("11. Configured algorithm selects SlidingWindowLimiter when SLIDING_WINDOW")
    void testSlidingWindowSelected() {
        contextRunner
                .withPropertyValues("rate-limit.algorithm=SLIDING_WINDOW")
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiter.class);
                    RateLimiter limiter = context.getBean(RateLimiter.class);
                    assertThat(limiter).isInstanceOf(SlidingWindowLimiter.class);
                });
    }
}
