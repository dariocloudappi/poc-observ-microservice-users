package com.example.microserviceusersapplication.controllers;

import com.example.microserviceusersapplication.dtos.SystemStatusResponse;
import com.example.microserviceusersapplication.services.SystemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/status")
    public ResponseEntity<SystemStatusResponse> getStatus() {
        SystemStatusResponse status = systemService.getStatus();
        return ResponseEntity.status(status.allOk() ? 200 : 503).body(status);
    }
}
