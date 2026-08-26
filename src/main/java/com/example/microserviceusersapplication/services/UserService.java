package com.example.microserviceusersapplication.services;

import com.example.microserviceusersapplication.exceptions.UserAlreadyExistsException;
import com.example.microserviceusersapplication.exceptions.UserNotFoundException;
import com.example.microserviceusersapplication.models.CreateUserRequest;
import com.example.microserviceusersapplication.models.UpdateUserRequest;
import com.example.microserviceusersapplication.models.User;
import com.example.microserviceusersapplication.observability.Observability;
import com.example.microserviceusersapplication.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Los atributos de negocio se anotan con {@link Observability}, no llamando a
 * Span.current().setAttribute directamente. Asi la misma clave queda
 * consultable en Span y en Log: antes solo estaba en Span, y por eso un
 * SELECT user.id FROM Log salia vacio.
 *
 * Nombre y email pasan por personalAttr, que escribe solo en el span. Son
 * datos personales y no aportan diagnostico adicional repetidos en cada linea
 * de log.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<User> getUsers() {
        log.debug("Listando usuarios: consultando el repositorio completo");
        long start = System.nanoTime();

        List<User> users = userRepository.findAll();

        long elapsedMs = elapsedMs(start);
        Observability.attr("db.operation", "findAll");
        Observability.attr("user.result_count", users.size());
        Observability.attr("db.duration_ms", elapsedMs);

        if (users.isEmpty()) {
            log.warn("Listado de usuarios vacio: la tabla no tiene registros");
        }
        log.atInfo()
                .addKeyValue("user.result_count", users.size())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Usuarios obtenidos: count={} en {} ms", users.size(), elapsedMs);
        return users;
    }

    public User getUser(UUID id) {
        Observability.attr("user.id", id.toString());
        log.debug("Buscando usuario: id={}", id);
        long start = System.nanoTime();

        User user = userRepository.findById(id)
                .orElseThrow(() -> {
                    // WARN y no ERROR: un id inexistente es un 404, o sea un
                    // error del cliente, no un fallo del servicio. Mezclarlos
                    // hace inservible cualquier alerta sobre nivel ERROR.
                    log.atWarn()
                            .addKeyValue("user.id", id.toString())
                            .addKeyValue("error.type", "UserNotFound")
                            .log("Usuario no encontrado: id={}", id);
                    return new UserNotFoundException(id.toString());
                });

        long elapsedMs = elapsedMs(start);
        Observability.attr("db.operation", "findById");
        Observability.attr("db.duration_ms", elapsedMs);
        Observability.personalAttr("user.name", user.getName());
        Observability.personalAttr("user.email", user.getEmail());

        log.atInfo()
                .addKeyValue("user.id", id.toString())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Usuario obtenido: id={} en {} ms", id, elapsedMs);
        return user;
    }

    public UUID createUser(CreateUserRequest request) {
        log.debug("Creando usuario: comprobando si el email ya existe");
        long start = System.nanoTime();

        if (userRepository.existsByEmail(request.getEmail())) {
            Observability.attr("user.create_rejected", "email_already_exists");
            log.atWarn()
                    .addKeyValue("error.type", "UserAlreadyExists")
                    .log("Creacion rechazada: el email ya esta registrado");
            throw new UserAlreadyExistsException(request.getEmail());
        }

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setCreatedAt(Instant.now());

        User savedUser = userRepository.save(user);
        UUID newId = savedUser.getId();

        long elapsedMs = elapsedMs(start);
        Observability.attr("user.id", newId.toString());
        Observability.attr("db.operation", "save");
        Observability.attr("db.duration_ms", elapsedMs);
        Observability.personalAttr("user.name", savedUser.getName());
        Observability.personalAttr("user.email", savedUser.getEmail());

        log.atInfo()
                .addKeyValue("user.id", newId.toString())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Usuario creado: id={} en {} ms", newId, elapsedMs);
        return newId;
    }

    public void updateUser(UUID id, UpdateUserRequest request) {
        Observability.attr("user.id", id.toString());
        log.debug("Actualizando usuario: id={}", id);
        long start = System.nanoTime();

        User user = userRepository.findById(id)
                .orElseThrow(() -> {
                    log.atWarn()
                            .addKeyValue("user.id", id.toString())
                            .addKeyValue("error.type", "UserNotFound")
                            .log("Actualizacion sobre usuario inexistente: id={}", id);
                    return new UserNotFoundException(id.toString());
                });

        // Que campos cambian es justo lo que se quiere saber al auditar un
        // update, y no se puede deducir del resto de la telemetria.
        boolean nameChanged = request.getName() != null;
        boolean emailChanged = request.getEmail() != null;
        if (nameChanged) {
            user.setName(request.getName());
        }
        if (emailChanged) {
            user.setEmail(request.getEmail());
        }
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        long elapsedMs = elapsedMs(start);
        Observability.attr("user.name_changed", nameChanged);
        Observability.attr("user.email_changed", emailChanged);
        Observability.attr("db.operation", "save");
        Observability.attr("db.duration_ms", elapsedMs);
        Observability.personalAttr("user.name", user.getName());
        Observability.personalAttr("user.email", user.getEmail());

        if (!nameChanged && !emailChanged) {
            log.atWarn()
                    .addKeyValue("user.id", id.toString())
                    .log("Actualizacion sin cambios: id={}, la peticion no traia campos", id);
        }
        log.atInfo()
                .addKeyValue("user.id", id.toString())
                .addKeyValue("user.name_changed", nameChanged)
                .addKeyValue("user.email_changed", emailChanged)
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Usuario actualizado: id={} (nombre={}, email={}) en {} ms",
                        id, nameChanged, emailChanged, elapsedMs);
    }

    public void deleteUser(UUID id) {
        Observability.attr("user.id", id.toString());
        log.debug("Eliminando usuario: id={}", id);
        long start = System.nanoTime();

        if (!userRepository.existsById(id)) {
            log.atWarn()
                    .addKeyValue("user.id", id.toString())
                    .addKeyValue("error.type", "UserNotFound")
                    .log("Borrado sobre usuario inexistente: id={}", id);
            throw new UserNotFoundException(id.toString());
        }
        userRepository.deleteById(id);

        long elapsedMs = elapsedMs(start);
        Observability.attr("db.operation", "deleteById");
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("user.id", id.toString())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Usuario eliminado: id={} en {} ms", id, elapsedMs);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
