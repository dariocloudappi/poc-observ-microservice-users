package com.example.microserviceusersapplication.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String id) {
        super("User not found: " + id);
    }
}
