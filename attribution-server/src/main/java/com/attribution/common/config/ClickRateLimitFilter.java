package com.attribution.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * IP-based rate limiting for the /api/v1/click endpoint.
 * Uses a Redis sliding-window counter to protect against DoS.
 * <p>
 * Default: 100 requests per minute per IP. Configure via
 * {@code attribution.click-rate-limit-max} and
 * {@code attribution.click-rate-limit-window-seconds}.
 */
@Component
public class ClickRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClickRateLimitFilter.class);
    private static final String RATE_LIMIT_KEY_PREFIX = "attribution:rate:click:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${attribution.click-rate-limit-max:100}")
    private int maxRequests;

    @Value("${attribution.click-rate-limit-window-seconds:60}")
    private int windowSeconds;

    public ClickRateLimitFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!requiresRateLimit(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);
        String key = RATE_LIMIT_KEY_PREFIX + clientIp;

        Long currentCount = stringRedisTemplate.opsForValue().increment(key);
        if (currentCount == null) {
            // Redis unavailable — fail open (log and allow)
            log.warn("Redis不可用，跳过速率限制: ip={}", clientIp);
            filterChain.doFilter(request, response);
            return;
        }

        if (currentCount == 1) {
            // First request in this window — set TTL
            stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }

        if (currentCount > maxRequests) {
            log.warn("速率限制触发: ip={}, count={}, max={}", clientIp, currentCount, maxRequests);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresRateLimit(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && "/api/v1/click".equals(request.getServletPath());
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take the first IP in the chain (original client)
            int comma = xForwardedFor.indexOf(',');
            return comma > 0 ? xForwardedFor.substring(0, comma).trim() : xForwardedFor.trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
