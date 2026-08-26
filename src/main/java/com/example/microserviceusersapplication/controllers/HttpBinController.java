package com.example.microserviceusersapplication.controllers;

import com.example.microserviceusersapplication.dtos.DataEnvelope;
import com.example.microserviceusersapplication.observability.Observability;
import com.example.microserviceusersapplication.services.HttpBinService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint de demostración de instrumentación de salida.
 *
 * Hace una llamada HTTP a httpbin.org/get y devuelve su respuesta tal cual.
 * Como httpbin devuelve las cabeceras que ha recibido, la respuesta de este
 * endpoint muestra exactamente qué se envió, incluida la cabecera traceparent
 * que inyecta el agente OpenTelemetry sin una línea de código.
 *
 * En la traza aparecen dos spans: el de servidor de esta petición y, colgando de
 * él, el de cliente de la llamada saliente.
 */
@RestController
@RequestMapping("/get")
public class HttpBinController {

    private static final Logger log = LoggerFactory.getLogger(HttpBinController.class);

    private final HttpBinService httpBinService;

    public HttpBinController(HttpBinService httpBinService) {
        this.httpBinService = httpBinService;
    }

    @GetMapping
    public ResponseEntity<DataEnvelope<Map<String, Object>>> get() {
        Observability.attr("api.operation", "httpbin.get");
        log.debug("Entrando en httpbin.get");

        Map<String, Object> payload = httpBinService.get();

        log.atInfo()
                .addKeyValue("api.operation", "httpbin.get")
                .addKeyValue("http.status_code", 200)
                .log("Respuesta de httpbin devuelta al cliente");
        return ResponseEntity.ok(new DataEnvelope<>(payload));
    }
}
