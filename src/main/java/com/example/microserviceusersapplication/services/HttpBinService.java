package com.example.microserviceusersapplication.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Llamada HTTP saliente de demostración contra httpbin.org.
 *
 * httpbin.org/get devuelve en su cuerpo las cabeceras que ha recibido, así que
 * es la forma más directa de comprobar qué se envía de verdad, incluida la
 * cabecera traceparent que inyecta el agente OpenTelemetry sin que el código
 * haga nada.
 */
@Service
public class HttpBinService {

    private static final Logger log = LoggerFactory.getLogger(HttpBinService.class);

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

        log.atInfo()
                .addKeyValue("http.client.dependency", "httpbin")
                .addKeyValue("url.full", httpBinUrl)
                .log("Calling httpbin to demonstrate outbound instrumentation");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                httpBinUrl,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        return response.getBody();
    }
}
