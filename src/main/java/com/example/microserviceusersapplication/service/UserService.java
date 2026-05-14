package com.example.microserviceusersapplication.service;

import com.example.microserviceusersapplication.exception.UserAlreadyExistsException;
import com.example.microserviceusersapplication.exception.UserNotFoundException;
import com.example.microserviceusersapplication.model.CreateUserRequest;
import com.example.microserviceusersapplication.model.UpdateUserRequest;
import com.example.microserviceusersapplication.model.User;
import com.example.microserviceusersapplication.repository.UserRepository;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<User> getUsers() {
        List<User> users = userRepository.findAll();
        Span.current().setAttribute("user.result_count", users.size());
        log.info("Usuarios obtenidos: count={}", users.size());
        return users;
    }

    public User getUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id.toString()));
        Span.current()
                .setAttribute("user.id", id.toString())
                .setAttribute("user.name", user.getName())
                .setAttribute("user.email", user.getEmail());
        log.info("Usuario obtenido: id={}", id);
        return user;
    }

    public UUID createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException(request.getEmail());
        }
        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setCreatedAt(Instant.now());
        User savedUser = userRepository.save(user);
        UUID newId = savedUser.getId();
        Span.current()
                .setAttribute("user.id", newId.toString())
                .setAttribute("user.name", savedUser.getName())
                .setAttribute("user.email", savedUser.getEmail());
        log.info("Usuario creado: id={}", newId);
        return newId;
    }

    public void updateUser(UUID id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id.toString()));
        if (request.getName() != null) user.setName(request.getName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        Span.current()
                .setAttribute("user.id", id.toString())
                .setAttribute("user.name", user.getName())
                .setAttribute("user.email", user.getEmail());
        log.info("Usuario actualizado: id={}", id);
    }

    public void deleteUser(UUID id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException(id.toString());
        }
        userRepository.deleteById(id);
        Span.current().setAttribute("user.id", id.toString());
        log.info("Usuario eliminado: id={}", id);
    }
}
