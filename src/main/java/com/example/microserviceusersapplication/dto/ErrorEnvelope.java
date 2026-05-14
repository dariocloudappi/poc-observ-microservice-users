package com.example.microserviceusersapplication.dto;

public record ErrorEnvelope(ErrorDetail error) {

    public ErrorEnvelope(String code, String message) {
        this(new ErrorDetail(code, message));
    }
}
