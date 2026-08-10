#!/bin/bash
# =============================================================================
# startup.sh - optional startup command for Azure App Service
# -----------------------------------------------------------------------------
# The pipeline does NOT need this script: the Java SE image already runs
# /home/site/wwwroot/app.jar, and the OpenTelemetry agent is attached through
# the JAVA_TOOL_OPTIONS app setting defined in infra/main.bicep.
#
# Use it only if you need to control the JVM flags by hand. In that case set
# the App Service startup command to:
#   bash /home/site/wwwroot/startup.sh
#
# The agent is attached here explicitly when JAVA_TOOL_OPTIONS does not already
# declare it, so the script instruments the application on its own. If the app
# setting is present, the JVM picks it up by itself and the script adds nothing,
# which avoids loading the agent twice.
# =============================================================================

set -euo pipefail

APP_JAR="${APP_JAR:-/home/site/wwwroot/app.jar}"
AGENT_JAR="${AGENT_JAR:-/home/site/wwwroot/otel-javaagent.jar}"

echo "[startup] Starting microservice-users"
echo "[startup] OTEL service name: ${OTEL_SERVICE_NAME:-not set}"
echo "[startup] OTEL endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:-not set}"
echo "[startup] JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:-not set}"

if [ ! -f "$APP_JAR" ]; then
    echo "[startup] ERROR: application jar not found at $APP_JAR" >&2
    exit 1
fi

# Decide whether this script has to attach the agent.
AGENT_OPTS=""
case "${JAVA_TOOL_OPTIONS:-}" in
    *-javaagent:*)
        echo "[startup] Agent already declared in JAVA_TOOL_OPTIONS, not adding it again"
        ;;
    *)
        if [ -f "$AGENT_JAR" ]; then
            AGENT_OPTS="-javaagent:${AGENT_JAR}"
            echo "[startup] Attaching the OpenTelemetry agent from ${AGENT_JAR}"
        else
            echo "[startup] WARNING: ${AGENT_JAR} not found, starting WITHOUT instrumentation" >&2
        fi
        ;;
esac

# AGENT_OPTS is intentionally unquoted: it is either empty or a single flag.
# shellcheck disable=SC2086
exec java \
    $AGENT_OPTS \
    -Xms256m \
    -Dserver.port="${PORT:-8080}" \
    -jar "$APP_JAR"
