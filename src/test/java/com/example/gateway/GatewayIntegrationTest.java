package com.example.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayIntegrationTest {

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        RedisTestSupport.configureRedis(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String VALID_KEY_1 = "test-api-key-1";
    private static final String VALID_KEY_2 = "test-api-key-2";

    @BeforeEach
    @AfterEach
    void cleanRedis() {
        redisTemplate.delete("rate_limit:token_bucket:api_key:" + VALID_KEY_1);
        redisTemplate.delete("rate_limit:token_bucket:api_key:" + VALID_KEY_2);
    }

    @Test
    @DisplayName("10. Missing API key receives HTTP 401 Unauthorized")
    void testMissingApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Missing or invalid API key"));
    }

    @Test
    @DisplayName("10. Invalid API key receives HTTP 401 Unauthorized")
    void testInvalidApiKeyReturns401() throws Exception {
        mockMvc.perform(get("/api/test")
                        .header("X-API-KEY", "non-existent-secret-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Missing or invalid API key"));
    }

    @Test
    @DisplayName("12. Requests allowed by the limiter reach the sample controller")
    void testAllowedRequestReachesController() throws Exception {
        mockMvc.perform(get("/api/test")
                        .header("X-API-KEY", VALID_KEY_1))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Request processed successfully"));
    }

    @Test
    @DisplayName("8 & 9. Gateway returns HTTP 429 and Retry-After header when rate limit is exceeded")
    void testRateLimitExceededReturns429WithRetryAfter() throws Exception {
        // Default capacity in application.yml is 5
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/test").header("X-API-KEY", VALID_KEY_1))
                    .andExpect(status().isOk());
        }

        // 6th request should be throttled
        mockMvc.perform(get("/api/test").header("X-API-KEY", VALID_KEY_1))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Retry-After", notNullValue()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value(containsString("Rate limit exceeded")));

        // Different API key should still succeed
        mockMvc.perform(get("/api/test").header("X-API-KEY", VALID_KEY_2))
                .andExpect(status().isOk());
    }
}
