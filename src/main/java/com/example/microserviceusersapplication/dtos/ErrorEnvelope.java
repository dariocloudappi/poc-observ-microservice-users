package com.example.microserviceusersapplication.dtos;

public record ErrorEnvelope(ErrorDetail error) {

    public ErrorEnvelope(String code, String message) {
        this(new ErrorDetail(code, message));
    }
}
