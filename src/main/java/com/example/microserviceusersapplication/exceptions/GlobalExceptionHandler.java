package com.example.microserviceusersapplication.exceptions;

import com.example.microserviceusersapplication.dtos.ErrorEnvelope;
import com.example.microserviceusersapplication.observability.Observability;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Ademas de traducir la excepcion a una respuesta, cada handler la registra en
 * el span.
 *
 * Por que importa: un @RestControllerAdvice CONSUME la excepcion. Al no
 * propagarse, el agente no la ve y el span queda sin marca de error. El
 * sintoma era que en New Relic se devolvia un 500 y la traza aparecia
 * aparentemente correcta, sin excepcion asociada y sin poder agrupar por tipo
 * de error. recordException y setStatus lo corrigen.
 *
 * Los 4xx NO se marcan como error del span a proposito: son errores del
 * cliente. Si se marcasen, la tasa de error del servicio incluiria cada 404 y
 * dejaria de servir para detectar averias reales.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(UserNotFoundException ex) {
        return clientError(ex, 404, "NOT_FOUND", "Recurso no encontrado");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));

        // Los campos que fallan se anotan aparte del mensaje: asi se puede
        // hacer un FACET por campo y ver que parte del contrato incumplen mas
        // los clientes.
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField())
                .distinct()
                .collect(Collectors.joining(","));
        Observability.attr("error.invalid_fields", fields);
        Observability.attr("error.invalid_field_count",
                ex.getBindingResult().getFieldErrorCount());

        annotateSpan(ex, 400, "BAD_REQUEST", false);
        log.atWarn()
                .addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "BAD_REQUEST")
                .addKeyValue("error.invalid_fields", fields)
                .addKeyValue("http.status_code", 400)
                .log("Validacion fallida: {}", message);
        return ResponseEntity.status(400).body(new ErrorEnvelope("BAD_REQUEST", message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNoResource(NoResourceFoundException ex) {
        return clientError(ex, 404, "NOT_FOUND", "Ruta inexistente");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return clientError(ex, 405, "METHOD_NOT_ALLOWED", "Metodo no permitido");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return clientError(ex, 415, "UNSUPPORTED_MEDIA_TYPE", "Tipo de contenido no soportado");
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorEnvelope> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        return clientError(ex, 409, "CONFLICT", "Conflicto: el usuario ya existe");
    }

    /**
     * Error provocado desde /force-errors.
     *
     * Reutiliza annotateSpan para que la telemetria sea IDENTICA a la de un
     * error real: si es 5xx se registra la excepcion en el span y su status pasa
     * a ERROR. Es lo que hace que sirva para probar una alerta de verdad.
     */
    @ExceptionHandler(ForcedErrorException.class)
    public ResponseEntity<ErrorEnvelope> handleForcedError(ForcedErrorException ex) {
        int status = ex.getStatus();
        boolean serverFault = status >= 500;

        // Marca que permite separar el ruido de las demos de los errores reales.
        Observability.attr("error.forced", true);
        annotateSpan(ex, status, "FORCED_ERROR", serverFault);

        var event = serverFault ? log.atError() : log.atWarn();
        event.addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "FORCED_ERROR")
                .addKeyValue("error.forced", true)
                .addKeyValue("http.status_code", status)
                .log("Error provocado {}: {}", status, ex.getMessage());

        return ResponseEntity.status(status)
                .body(new ErrorEnvelope("FORCED_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGeneral(Exception ex) {
        // Unico caso que SI marca el span como error: aqui si es un fallo del
        // servicio.
        annotateSpan(ex, 500, "INTERNAL_ERROR", true);
        log.atError()
                .addKeyValue("error.type", ex.getClass().getName())
                .addKeyValue("error.code", "INTERNAL_ERROR")
                .addKeyValue("http.status_code", 500)
                .setCause(ex)
                .log("Error inesperado: {}", ex.getMessage());
        return ResponseEntity.status(500)
                .body(new ErrorEnvelope("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private ResponseEntity<ErrorEnvelope> clientError(Exception ex, int status,
                                                      String code, String summary) {
        annotateSpan(ex, status, code, false);
        log.atWarn()
                .addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", code)
                .addKeyValue("http.status_code", status)
                .log("{}: {}", summary, ex.getMessage());
        return ResponseEntity.status(status).body(new ErrorEnvelope(code, ex.getMessage()));
    }

    private void annotateSpan(Exception ex, int status, String code, boolean serverFault) {
        Observability.attr("error.type", ex.getClass().getSimpleName());
        Observability.attr("error.code", code);
        Observability.attr("error.handled", true);

        Span span = Span.current();
        if (serverFault) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, code);
        } else {
            // El tipo se registra igual para poder agrupar, pero sin tocar el
            // status del span.
            span.setAttribute("error.client_status", status);
        }
    }
}
