package com.example.microserviceusersapplication.filters;

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

        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString() != null ? request.getQueryString() : "";

        Span span = Span.current();

        span.setAttribute("url.query", query);
        safeRequestHeaders(request).forEach((name, value) ->
                span.setAttribute("http.request.header." + name.toLowerCase(), value));

        MDC.put("http.method", method);
        MDC.put("http.url", path);
        MDC.put("url.query", query);

        // El metodo y la ruta viajan dos veces a proposito: dentro del mensaje,
        // para que sea legible tal cual, y como atributos estructurados, que son
        // los que permiten filtrar y agrupar en New Relic.
        log.atInfo()
                .addKeyValue("http.method", method)
                .addKeyValue("http.target", path)
                .addKeyValue("http.query", query)
                .addKeyValue("http.client_ip", request.getRemoteAddr())
                .log("{} Starting request {}", method, path);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;

            safeResponseHeaders(response).forEach((name, value) ->
                    span.setAttribute("http.response.header." + name.toLowerCase(), value));

            MDC.put("http.status_code", String.valueOf(response.getStatus()));
            MDC.put("http.duration_ms", String.valueOf(duration));

            log.atInfo()
                    .addKeyValue("http.method", method)
                    .addKeyValue("http.target", path)
                    .addKeyValue("http.status_code", response.getStatus())
                    .addKeyValue("http.duration_ms", duration)
                    .log("{} Ending request {} {} (ms)", method, path, duration);

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