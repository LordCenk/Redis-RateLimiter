package com.example.gateway.config;

import com.example.gateway.limiter.RateLimiter;
import com.example.gateway.limiter.SlidingWindowLimiter;
import com.example.gateway.limiter.TokenBucketLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties({RateLimitConfig.RateLimitProperties.class, RedisProperties.class})
public class RateLimitConfig {

    public enum Algorithm {
        TOKEN_BUCKET,
        SLIDING_WINDOW
    }

    public enum IdentityType {
        API_KEY,
        USER,
        IP
    }

    public static class ApiKeyEntry {
        private String key;
        private String username;

        public ApiKeyEntry() {}

        public ApiKeyEntry(String key, String username) {
            this.key = key;
            this.username = username;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    @ConfigurationProperties(prefix = "rate-limit")
    public static class RateLimitProperties {
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
        private long capacity = 5;
        private double refillRate = 1.0;
        private long windowSeconds = 10;
        private long maxRequests = 5;
        private IdentityType identityType = IdentityType.API_KEY;
        private List<ApiKeyEntry> apiKeys = new ArrayList<>();

        public Algorithm getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(Algorithm algorithm) {
            this.algorithm = algorithm;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.capacity = capacity;
        }

        public double getRefillRate() {
            return refillRate;
        }

        public void setRefillRate(double refillRate) {
            if (refillRate <= 0) {
                throw new IllegalArgumentException("refill-rate must be positive");
            }
            this.refillRate = refillRate;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("window-seconds must be positive");
            }
            this.windowSeconds = windowSeconds;
        }

        public long getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(long maxRequests) {
            if (maxRequests <= 0) {
                throw new IllegalArgumentException("max-requests must be positive");
            }
            this.maxRequests = maxRequests;
        }

        public IdentityType getIdentityType() {
            return identityType;
        }

        public void setIdentityType(IdentityType identityType) {
            this.identityType = identityType;
        }

        public List<ApiKeyEntry> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(List<ApiKeyEntry> apiKeys) {
            this.apiKeys = apiKeys;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties redisProperties) {
        String host = redisProperties.getHost();
        int port = redisProperties.getPort();

        // Intelligently fallback to WSL IP on Windows if localhost/127.0.0.1 is not reachable
        host = resolveReachableHost(host, port);

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            config.setPassword(redisProperties.getPassword());
        }
        return new LettuceConnectionFactory(config);
    }

    private String resolveReachableHost(String host, int port) {
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return "127.0.0.1";
            } catch (Exception ignored) {
            }

            try {
                Process p = Runtime.getRuntime().exec(new String[]{"wsl", "hostname", "-I"});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        String wslIp = line.trim().split("\\s+")[0];
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(wslIp, port), 400);
                            return wslIp;
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return host;
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> slidingWindowScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    public RateLimiter rateLimiter(
            RateLimitProperties properties,
            StringRedisTemplate redisTemplate,
            RedisScript<List> tokenBucketScript,
            RedisScript<List> slidingWindowScript,
            Clock clock
    ) {
        if (properties.getAlgorithm() == Algorithm.TOKEN_BUCKET) {
            return new TokenBucketLimiter(redisTemplate, tokenBucketScript, properties, clock);
        } else if (properties.getAlgorithm() == Algorithm.SLIDING_WINDOW) {
            return new SlidingWindowLimiter(redisTemplate, slidingWindowScript, properties, clock);
        }
        throw new IllegalStateException("Unknown rate limit algorithm: " + properties.getAlgorithm());
    }
}
