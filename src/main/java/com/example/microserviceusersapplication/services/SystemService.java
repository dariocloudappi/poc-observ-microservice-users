package com.example.microserviceusersapplication.services;

import com.example.microserviceusersapplication.dtos.ServiceStatus;
import com.example.microserviceusersapplication.dtos.SystemStatusResponse;
import com.example.microserviceusersapplication.observability.Observability;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * El health check anota su resultado como atributo, no solo en el cuerpo de la
 * respuesta.
 *
 * Sin eso, la unica forma de saber en New Relic si la base de datos estaba
 * disponible era leer el JSON devuelto, que no es consultable. Con
 * health.database.status y health.database.duration_ms se puede graficar la
 * disponibilidad y la latencia del chequeo directamente en NRQL.
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private final JdbcTemplate jdbcTemplate;

    public SystemService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SystemStatusResponse getStatus() {
        log.debug("Ejecutando comprobacion de estado del sistema");
        ServiceStatus database = checkDatabase();

        // ServiceStatus es un record en este servicio, asi que el accessor es
        // status() y no getStatus(). En orders la misma clase es un POJO con
        // getters: son dos formas distintas del mismo concepto y conviene
        // saberlo al copiar codigo entre los dos micros.
        boolean healthy = "ok".equals(database.status());
        Observability.attr("health.overall", healthy ? "ok" : "degraded");

        if (!healthy) {
            log.atWarn()
                    .addKeyValue("health.overall", "degraded")
                    .log("Estado del sistema degradado: la base de datos no responde");
        } else {
            log.atInfo()
                    .addKeyValue("health.overall", "ok")
                    .log("Estado del sistema correcto");
        }
        return new SystemStatusResponse(List.of(database));
    }

    private ServiceStatus checkDatabase() {
        long start = System.nanoTime();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            long elapsedMs = elapsedMs(start);
            Observability.attr("health.database.status", "ok");
            Observability.attr("health.database.duration_ms", elapsedMs);

            log.atDebug()
                    .addKeyValue("health.database.duration_ms", elapsedMs)
                    .log("Base de datos accesible: SELECT 1 en {} ms", elapsedMs);
            return new ServiceStatus("database", "ok");

        } catch (Exception e) {
            long elapsedMs = elapsedMs(start);
            Observability.attr("health.database.status", "error");
            Observability.attr("health.database.duration_ms", elapsedMs);
            Observability.attr("error.type", e.getClass().getSimpleName());

            // La excepcion se registra en el span: el health check la captura y
            // devuelve 200, asi que sin esto el fallo de base de datos no
            // aparece en la traza por ningun lado.
            Span.current().recordException(e);

            log.atError()
                    .addKeyValue("health.database.status", "error")
                    .addKeyValue("health.database.duration_ms", elapsedMs)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Comprobacion de base de datos fallida tras {} ms: {}",
                            elapsedMs, e.getMessage());
            return new ServiceStatus("database", shortMessage(e));
        }
    }

    private String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
