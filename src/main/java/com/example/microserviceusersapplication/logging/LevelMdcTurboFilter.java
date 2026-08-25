package com.example.microserviceusersapplication.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

/**
 * Escribe el nivel del evento en MDC["level"] antes de que Logback lo procese.
 *
 * Por qué hace falta: el agente OTel exporta el nivel como el campo
 * severityText del protocolo, que no siempre queda expuesto como atributo
 * consultable. El MDC sí lo exporta como atributo explícito cuando
 * OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=*
 * está activo, así que con esto se puede filtrar por level en NRQL:
 *
 *   SELECT count(*) FROM Log WHERE service.name = 'microservice-users'
 *   SINCE 30 minutes ago FACET level
 *
 * Es un TurboFilter y no un appender a propósito: se ejecuta antes de que el
 * evento llegue a cualquier appender, así que el valor ya está en el MDC cuando
 * el appender del agente construye el LogRecord.
 */
public class LevelMdcTurboFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (level != null) {
            MDC.put("level", level.toString());
        }
        return FilterReply.NEUTRAL;
    }
}
