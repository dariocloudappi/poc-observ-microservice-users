package com.example.microserviceusersapplication.filter;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.Order;
@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter{

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-api-key",
            "x-auth-token",
            "x-forwarded-authorization"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        Span span = Span.current();

        span.setAttribute("url.query", request.getQueryString() != null ? request.getQueryString() : "");
        safeRequestHeaders(request).forEach((name, value) ->
                span.setAttribute("http.request.header." + name.toLowerCase(), value));

        MDC.put("http.method", request.getMethod());
        MDC.put("http.url", request.getRequestURI());
        MDC.put("url.query", request.getQueryString() != null ? request.getQueryString() : "");

        log.info("Starting request");

        try {
            filterChain.doFilter(request, response);
        } finally {
            safeResponseHeaders(response).forEach((name, value) ->
                    span.setAttribute("http.response.header." + name.toLowerCase(), value));

            MDC.put("http.status_code", String.valueOf(response.getStatus()));
            MDC.put("http.duration_ms", String.valueOf(System.currentTimeMillis() - start));

            log.info("Ending request");

            MDC.remove("http.method");
            MDC.remove("http.url");
            MDC.remove("url.query");
            MDC.remove("http.status_code");
            MDC.remove("http.duration_ms");
        }
    }

    private Map<String, String> safeRequestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, request.getHeader(name));
            }
        });
        return headers;
    }

    private Map<String, String> safeResponseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaderNames().forEach(name -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, response.getHeader(name));
            }
        });
        return headers;
    }
}