package com.example.microserviceusersapplication.controllers;

import com.example.microserviceusersapplication.dtos.SystemStatusResponse;
import com.example.microserviceusersapplication.observability.Observability;
import com.example.microserviceusersapplication.services.SystemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/status")
    public ResponseEntity<SystemStatusResponse> getStatus() {
        Observability.attr("api.operation", "system.status");
        log.debug("Entrando en system.status");

        SystemStatusResponse status = systemService.getStatus();
        int httpStatus = status.allOk() ? 200 : 503;
        Observability.attr("health.http_status", httpStatus);

        // El 503 se registra explicitamente: es la senal que consume cualquier
        // alerta de disponibilidad, y sin log queda solo en el codigo HTTP.
        if (httpStatus == 503) {
            log.atError()
                    .addKeyValue("api.operation", "system.status")
                    .addKeyValue("http.status_code", 503)
                    .log("Health check devolviendo 503: hay dependencias caidas");
        } else {
            log.atInfo()
                    .addKeyValue("api.operation", "system.status")
                    .addKeyValue("http.status_code", 200)
                    .log("Health check correcto");
        }
        return ResponseEntity.status(httpStatus).body(status);
    }
}
