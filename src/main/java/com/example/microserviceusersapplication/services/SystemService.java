package com.example.microserviceusersapplication.services;

import com.example.microserviceusersapplication.dtos.ServiceStatus;
import com.example.microserviceusersapplication.dtos.SystemStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private final JdbcTemplate jdbcTemplate;

    public SystemService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SystemStatusResponse getStatus() {
        return new SystemStatusResponse(List.of(checkDatabase()));
    }

    private ServiceStatus checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ServiceStatus("database", "ok");
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage(), e);
            return new ServiceStatus("database", shortMessage(e));
        }
    }

    private String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
