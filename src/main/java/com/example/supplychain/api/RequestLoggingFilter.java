package com.example.supplychain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ObjectMapper mapper;
    public RequestLoggingFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            String event = mapper.writeValueAsString(Map.of("event", "http_request", "method", request.getMethod(), "path", request.getRequestURI(),
                    "status_code", response.getStatus(), "latency_ms", Math.round((System.nanoTime() - start) / 1_000_000.0 * 100.0) / 100.0));
            log.info(event);
        }
    }
}
