package com.example.gateway.service;

import com.example.gateway.config.RateLimitConfig.IdentityType;
import com.example.gateway.config.RateLimitConfig.RateLimitProperties;
import com.example.gateway.config.SecurityConfig.ApiKeyAuthenticationToken;
import com.example.gateway.limiter.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RateLimitServiceTest {

    private final RateLimiter limiter = mock(RateLimiter.class);

    @Test
    @DisplayName("Per-API-Key identity resolution")
    void testResolveApiKeyIdentity() {
        RateLimitProperties props = new RateLimitProperties();
        props.setIdentityType(IdentityType.API_KEY);
        RateLimitService service = new RateLimitService(limiter, props);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-KEY", "my-secret-key");
        Authentication auth = new ApiKeyAuthenticationToken("user1", "my-secret-key");

        String identity = service.resolveIdentity(request, auth);
        assertEquals("api_key:my-secret-key", identity);
    }

    @Test
    @DisplayName("Per-User identity resolution")
    void testResolveUserIdentity() {
        RateLimitProperties props = new RateLimitProperties();
        props.setIdentityType(IdentityType.USER);
        RateLimitService service = new RateLimitService(limiter, props);

        MockHttpServletRequest request = new MockHttpServletRequest();
        Authentication auth = new ApiKeyAuthenticationToken("alice_user", "key123");

        String identity = service.resolveIdentity(request, auth);
        assertEquals("user:alice_user", identity);
    }

    @Test
    @DisplayName("Per-IP identity resolution (ignores client-supplied forwarding headers)")
    void testResolveIpIdentity() {
        RateLimitProperties props = new RateLimitProperties();
        props.setIdentityType(IdentityType.IP);
        RateLimitService service = new RateLimitService(limiter, props);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        // Untrusted spoofed header should be ignored
        request.addHeader("X-Forwarded-For", "10.0.0.1");

        Authentication auth = new ApiKeyAuthenticationToken("user1", "key123");

        String identity = service.resolveIdentity(request, auth);
        assertEquals("ip:192.168.1.100", identity);
    }
}
