package com.attribution.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ReportApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Attribution-Api-Key";

    private final String apiKey;

    public ReportApiKeyFilter(@Value("${attribution.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!requiresApiKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (!matches(providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":401,\"message\":\"无效的上报API密钥\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean requiresApiKey(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && "/api/v1/report".equals(request.getServletPath());
    }

    private boolean matches(String providedKey) {
        if (providedKey == null || providedKey.isBlank() || apiKey == null || apiKey.isBlank()) {
            return false;
        }
        byte[] expected = apiKey.getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
