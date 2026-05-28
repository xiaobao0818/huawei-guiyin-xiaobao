package com.attribution.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class DebugApiKeyFilter extends OncePerRequestFilter {

    private static final String DEBUG_KEY_HEADER = "X-Attribution-Debug";

    @Value("${attribution.debug-api-key:}")
    private String debugApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        if (debugApiKey != null && !debugApiKey.isEmpty()) {
            String debugKey = request.getHeader(DEBUG_KEY_HEADER);
            if (debugApiKey.equals(debugKey)) {
                request.setAttribute("debug_mode", true);
            }
        }
        filterChain.doFilter(request, response);
    }
}
