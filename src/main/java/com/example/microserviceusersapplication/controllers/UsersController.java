package com.example.microserviceusersapplication.controllers;

import com.example.microserviceusersapplication.dtos.DataEnvelope;
import com.example.microserviceusersapplication.dtos.UserCreateResponse;
import com.example.microserviceusersapplication.models.CreateUserRequest;
import com.example.microserviceusersapplication.models.UpdateUserRequest;
import com.example.microserviceusersapplication.models.User;
import com.example.microserviceusersapplication.observability.Observability;
import com.example.microserviceusersapplication.services.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cada endpoint anota api.operation.
 *
 * No es redundante con http.route: la ruta la fija el agente y agrupa por
 * plantilla de URL, mientras que api.operation nombra la operacion de negocio.
 * Es lo que permite un FACET api.operation en NRQL sin depender de como este
 * escrita la ruta, y sigue funcionando si la ruta cambia de version.
 */
@RestController
@RequestMapping("/users")
public class UsersController {

    private static final Logger log = LoggerFactory.getLogger(UsersController.class);

    private final UserService userService;

    public UsersController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<DataEnvelope<List<User>>> getUsers() {
        Observability.attr("api.operation", "users.list");
        log.debug("Entrando en users.list");
        return ResponseEntity.ok(new DataEnvelope<>(userService.getUsers()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataEnvelope<User>> getUserById(@PathVariable UUID id) {
        Observability.attr("api.operation", "users.get");
        log.debug("Entrando en users.get: id={}", id);
        return ResponseEntity.ok(new DataEnvelope<>(userService.getUser(id)));
    }

    @PostMapping
    public ResponseEntity<DataEnvelope<UserCreateResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        Observability.attr("api.operation", "users.create");
        log.debug("Entrando en users.create");

        UUID id = userService.createUser(request);

        log.atInfo()
                .addKeyValue("api.operation", "users.create")
                .addKeyValue("user.id", id.toString())
                .addKeyValue("http.status_code", 201)
                .log("Usuario creado y devuelto con 201: id={}", id);
        return ResponseEntity.status(201)
                .body(new DataEnvelope<>(new UserCreateResponse(id.toString())));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        Observability.attr("api.operation", "users.update");
        log.debug("Entrando en users.update: id={}", id);

        userService.updateUser(id, request);

        log.atInfo()
                .addKeyValue("api.operation", "users.update")
                .addKeyValue("user.id", id.toString())
                .addKeyValue("http.status_code", 204)
                .log("Usuario actualizado, devolviendo 204: id={}", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        Observability.attr("api.operation", "users.delete");
        log.debug("Entrando en users.delete: id={}", id);

        userService.deleteUser(id);

        log.atInfo()
                .addKeyValue("api.operation", "users.delete")
                .addKeyValue("user.id", id.toString())
                .addKeyValue("http.status_code", 204)
                .log("Usuario eliminado, devolviendo 204: id={}", id);
        return ResponseEntity.noContent().build();
    }
}
