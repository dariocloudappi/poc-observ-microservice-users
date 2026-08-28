package com.example.microserviceusersapplication.services;

import com.example.microserviceusersapplication.observability.Observability;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Llamada HTTP saliente de demostracion contra httpbin.org.
 *
 * httpbin.org/get devuelve en su cuerpo las cabeceras recibidas, lo que permite
 * comprobar que se envia realmente, incluida la cabecera traceparent que
 * inyecta el agente OpenTelemetry sin intervencion del codigo.
 */
@Service
public class HttpBinService {

    private static final Logger log = LoggerFactory.getLogger(HttpBinService.class);

    private static final String DEPENDENCY = "httpbin";

    private final RestTemplate restTemplate;
    private final String httpBinUrl;

    public HttpBinService(RestTemplate httpBinRestTemplate,
                          @Value("${app.httpbin.url:https://httpbin.org/get}") String httpBinUrl) {
        this.restTemplate = httpBinRestTemplate;
        this.httpBinUrl = httpBinUrl;
    }

    public Map<String, Object> get() {
        HttpHeaders headers = new HttpHeaders();
        // Cabecera propia, para verla de vuelta en la respuesta de httpbin y
        // confirmar que lo que se envia es lo que se cree que se envia.
        headers.set("X-Poc-Source", "microservice-users");

        Observability.attr("http.client.dependency", DEPENDENCY);
        Observability.attr("url.full", httpBinUrl);

        log.atInfo()
                .addKeyValue("http.client.dependency", DEPENDENCY)
                .addKeyValue("url.full", httpBinUrl)
                .log("Llamando a httpbin para demostrar la instrumentacion saliente");

        long start = System.nanoTime();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    httpBinUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});

            long elapsedMs = elapsedMs(start);
            int status = response.getStatusCode().value();
            Map<String, Object> body = response.getBody();

            Observability.attr("http.client.status_code", status);
            Observability.attr("http.client.duration_ms", elapsedMs);
            Observability.attr("http.client.response_keys", body == null ? 0 : body.size());

            log.atInfo()
                    .addKeyValue("http.client.dependency", DEPENDENCY)
                    .addKeyValue("http.client.status_code", status)
                    .addKeyValue("http.client.duration_ms", elapsedMs)
                    .log("httpbin respondio {} en {} ms", status, elapsedMs);
            return body;

        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(start);
            Observability.attr("http.client.duration_ms", elapsedMs);
            Observability.attr("error.type", e.getClass().getSimpleName());
            Span.current().recordException(e);

            // ERROR y no WARN: aqui la dependencia saliente ha fallado de
            // verdad, no es un error de uso del cliente.
            log.atError()
                    .addKeyValue("http.client.dependency", DEPENDENCY)
                    .addKeyValue("url.full", httpBinUrl)
                    .addKeyValue("http.client.duration_ms", elapsedMs)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Llamada a httpbin fallida tras {} ms: {}", elapsedMs, e.getMessage());
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
