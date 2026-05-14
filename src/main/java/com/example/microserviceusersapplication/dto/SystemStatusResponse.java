package com.example.microserviceusersapplication.dto;

import java.util.List;

public record SystemStatusResponse(List<ServiceStatus> data) {

    public boolean allOk() {
        return data.stream().allMatch(s -> "ok".equals(s.status()));
    }
}
