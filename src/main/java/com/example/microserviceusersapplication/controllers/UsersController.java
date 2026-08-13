package com.example.microserviceusersapplication.controllers;

import com.example.microserviceusersapplication.dtos.DataEnvelope;
import com.example.microserviceusersapplication.dtos.UserCreateResponse;
import com.example.microserviceusersapplication.models.CreateUserRequest;
import com.example.microserviceusersapplication.models.UpdateUserRequest;
import com.example.microserviceusersapplication.models.User;
import com.example.microserviceusersapplication.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UsersController {

    private final UserService userService;

    public UsersController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<DataEnvelope<List<User>>> getUsers() {
        return ResponseEntity.ok(new DataEnvelope<>(userService.getUsers()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataEnvelope<User>> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(new DataEnvelope<>(userService.getUser(id)));
    }

    @PostMapping
    public ResponseEntity<DataEnvelope<UserCreateResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        UUID id = userService.createUser(request);
        return ResponseEntity.status(201).body(new DataEnvelope<>(new UserCreateResponse(id.toString())));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        userService.updateUser(id, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
