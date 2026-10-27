package com.example.gateway.service;

import com.example.gateway.config.RateLimitConfig.IdentityType;
import com.example.gateway.config.RateLimitConfig.RateLimitProperties;
import com.example.gateway.limiter.RateLimitResult;
import com.example.gateway.limiter.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public RateLimitService(RateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public RateLimitResult checkRateLimit(HttpServletRequest request, Authentication authentication) {
        String identity = resolveIdentity(request, authentication);
        return rateLimiter.tryAcquire(identity);
    }

    public String resolveIdentity(HttpServletRequest request, Authentication authentication) {
        IdentityType identityType = properties.getIdentityType();
        return switch (identityType) {
            case API_KEY -> {
                String apiKey = null;
                if (authentication != null && authentication.getCredentials() != null) {
                    apiKey = authentication.getCredentials().toString();
                } else if (request != null) {
                    apiKey = request.getHeader("X-API-KEY");
                }
                if (apiKey == null || apiKey.isBlank()) {
                    apiKey = "anonymous";
                }
                yield "api_key:" + apiKey.trim();
            }
            case USER -> {
                String username = (authentication != null && authentication.getName() != null)
                        ? authentication.getName()
                        : "anonymous";
                yield "user:" + username;
            }
            case IP -> {
                String remoteAddr = (request != null) ? request.getRemoteAddr() : "unknown";
                yield "ip:" + (remoteAddr != null ? remoteAddr : "unknown");
            }
        };
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
