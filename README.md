# PoC microservice-users - Azure App Service + Azure SQL + New Relic

API REST de usuarios (Spring Boot 3.2.5, Java 17) desplegada en **Azure App Service Linux**
con **Azure SQL Database**, instrumentada con el **agente OpenTelemetry Java (zero-code)**
hacia **New Relic**, y con **Azure Monitor** recogiendo los logs y métricas de plataforma.

No hay una sola línea de instrumentación en el código de negocio: el agente se adjunta al
arrancar y de ahí salen trazas, métricas y logs correlacionados.

Todo el despliegue es automático desde GitHub Actions con autenticación **OIDC federada** (sin
client secrets) y está dimensionado para el mínimo coste posible en un PoC de vida corta.

| Si quieres... | Ve a |
|---------------|------|
| Llamar a la API | [3. Cómo usar la API](#3-cómo-usar-la-api) |
| Ver trazas, métricas y logs en New Relic | [4. Cómo ver la telemetría en New Relic](#4-cómo-ver-la-telemetría-en-new-relic) |
| Ver lo que pasa en la base de datos | [5. Telemetría de base de datos](#5-telemetría-de-base-de-datos) |
| Consultar los logs de plataforma de Azure | [6. Azure Monitor y Log Analytics](#6-azure-monitor-y-log-analytics) |
| Arreglar que no llegan datos | [7. Si no llega telemetría](#7-si-no-llega-telemetría) |
| Desplegarlo desde cero | [8. Configuración previa](#8-configuración-previa) y [9. Despliegue](#9-despliegue) |
| Borrarlo para no pagar | [13. Limpieza de recursos](#13-limpieza-de-recursos) |

---

## 1. Arquitectura

```mermaid
flowchart LR
    client([Cliente / Tyk Gateway])

    subgraph azure["Azure - Resource Group rg-usersvc (tags: environment=poc, ttl=1h)"]
        subgraph plan["App Service Plan Linux B1"]
            app["Web App Java SE 17<br/>app.jar + otel-javaagent.jar<br/>HTTPS only, TLS 1.2+"]
        end
        sql[("Azure SQL Database<br/>Basic 5 DTU / 2 GB")]
        law[("Log Analytics<br/>Workspace")]
    end

    nr["New Relic<br/>OTLP http/protobuf"]

    client -->|HTTPS + Basic Auth| app
    app -->|JDBC TLS 1.2| sql
    app -->|traces + metrics + logs| nr
    app -.->|HTTP, console, app, platform logs| law
    sql -.->|errors, timeouts, deadlocks, query store| law
```

### Recursos creados

| Recurso | Nombre | SKU / configuración |
|---------|--------|---------------------|
| Resource Group | `rg-usersvc` | tags `environment=poc`, `ttl=1h`, `owner`, `project`, `createdAt` |
| Log Analytics Workspace | `log-usersvc` | PerGB2018, retención 30 días, cuota diaria 1 GB |
| App Service Plan | `plan-usersvc` | Linux **B1** (configurable a F1 o B2) |
| Web App | `app-usersvc-<hash>` | Java SE 17, `httpsOnly`, TLS 1.2, FTPS deshabilitado, Always On, health check en `/actuator/health`, identidad administrada de sistema |
| SQL Server | `sql-usersvc-<hash>` | TLS mínimo 1.2, regla de firewall solo para servicios de Azure |
| SQL Database | `sqldb-users` | **Basic** 5 DTU, 2 GB, backup local |
| Diagnostic Settings | `diag-usersvc-app`, `diag-sqldb-users` | `allLogs` + métricas hacia Log Analytics |

---

## 2. Estructura del proyecto

```
src/main/java/com/example/microserviceusersapplication/
├── config/         SecurityConfig, WebConfig, TraceIdInterceptor
├── controllers/    UsersController, SystemController
├── dtos/           envolturas de respuesta (DataEnvelope, ErrorEnvelope...)
├── exceptions/     GlobalExceptionHandler y excepciones de dominio
├── filters/        RequestLoggingFilter (log de entrada/salida + atributos de traza)
├── models/         entidad User y requests de entrada
├── repository/     UserRepository (Spring Data JPA)
└── services/       UserService, SystemService

infra/
├── main.bicep              infraestructura del PoC (scope: suscripción)
├── main.bicepparam         parámetros leídos de variables de entorno
├── newrelic.bicep          integración nativa de New Relic (una vez por suscripción)
└── modules/                appservice, sql, monitoring, newrelic-monitor

.github/workflows/
├── deploy.yml                      build + infra + despliegue + smoke tests
├── destroy.yml                     borrado manual y limpieza programada
├── newrelic-native-integration.yml integración nativa de Azure con New Relic
└── newrelic-azure-integration.yml  integración por polling (alternativa)
```

---

## 3. Cómo usar la API

### 3.1 Obtener la URL

```bash
APP=$(az webapp list -g rg-usersvc --query "[0].name" -o tsv)
URL="https://$(az webapp show -g rg-usersvc -n "$APP" --query defaultHostName -o tsv)"
echo "$URL"
```

Las credenciales son las de los secrets `BASIC_AUTH_USER` y `BASIC_AUTH_PASSWORD`:

```bash
export BASIC_AUTH_USER=...  BASIC_AUTH_PASSWORD=...
AUTH="-u $BASIC_AUTH_USER:$BASIC_AUTH_PASSWORD"
```

### 3.2 Endpoints

| Método | Ruta | Autenticación | Respuesta correcta |
|--------|------|---------------|--------------------|
| `GET` | `/actuator/health` | pública | `200` con el estado de la app y de la base de datos |
| `GET` | `/actuator/info` | pública | `200` con la información de la build |
| `GET` | `/actuator/metrics` | pública | `200` con la lista de métricas de Micrometer |
| `GET` | `/actuator/prometheus` | pública | `200` con las métricas en formato Prometheus |
| `GET` | `/status` | Basic Auth | `200` si la base de datos responde, `503` si no |
| `GET` | `/users` | Basic Auth | `200` con la lista de usuarios |
| `GET` | `/users/{id}` | Basic Auth | `200` con el usuario, `404` si no existe |
| `POST` | `/users` | Basic Auth | `201` con el id creado, `409` si el email ya existe |
| `PATCH` | `/users/{id}` | Basic Auth | `204` sin cuerpo |
| `DELETE` | `/users/{id}` | Basic Auth | `204` sin cuerpo |

El esquema de base de datos lo crea Hibernate al arrancar (`ddl-auto: update`), no hay paso de
migración: la tabla `users` aparece sola en el primer arranque.

### 3.3 Ejemplos

```bash
# Salud, sin credenciales
curl -s "$URL/actuator/health" | jq

# Estado de las dependencias (hace un SELECT 1 real contra Azure SQL)
curl -s $AUTH "$URL/status" | jq
# {"data":[{"service":"database","status":"ok"}]}

# Alta de usuario
curl -s -X POST $AUTH "$URL/users" \
  -H "Content-Type: application/json" \
  -d '{"name":"Ada Lovelace","email":"ada@example.com"}' | jq
# {"data":{"id":"3f7c1b8e-..."}}

ID=$(curl -s -X POST $AUTH "$URL/users" -H "Content-Type: application/json" \
  -d '{"name":"Alan Turing","email":"alan@example.com"}' | jq -r .data.id)

# Listado y consulta por id
curl -s $AUTH "$URL/users" | jq
curl -s $AUTH "$URL/users/$ID" | jq
# {"data":{"id":"...","name":"Alan Turing","email":"alan@example.com",
#          "createdAt":"2026-01-01T10:00:00Z","updatedAt":null}}

# Modificación parcial: los campos ausentes no se tocan
curl -s -o /dev/null -w "%{http_code}\n" -X PATCH $AUTH "$URL/users/$ID" \
  -H "Content-Type: application/json" -d '{"name":"Alan M. Turing"}'
# 204

# Baja
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE $AUTH "$URL/users/$ID"
# 204
```

Cuerpos y validaciones:

| Campo | `POST /users` | `PATCH /users/{id}` |
|-------|---------------|---------------------|
| `name` | obligatorio, no vacío | opcional |
| `email` | obligatorio, formato de email | opcional, formato de email |

### 3.4 Formato de las respuestas

Todo lo correcto viaja envuelto en `data`, y todo lo que falla en `error`:

```json
{ "data": { "id": "3f7c1b8e-..." } }
```

```json
{ "error": { "code": "NOT_FOUND", "message": "User 3f7c1b8e-... not found" } }
```

| Código HTTP | `error.code` | Cuándo |
|-------------|--------------|--------|
| `400` | `BAD_REQUEST` | Falla la validación del cuerpo |
| `401` | — | Falta o no vale el Basic Auth |
| `404` | `NOT_FOUND` | El usuario o la ruta no existen |
| `405` | `METHOD_NOT_ALLOWED` | Método no soportado en esa ruta |
| `409` | `CONFLICT` | El email ya está registrado |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Falta `Content-Type: application/json` |
| `500` | `INTERNAL_ERROR` | Error inesperado; el detalle real solo va al log |

### 3.5 La cabecera `X-Trace-Id`

Cada respuesta lleva la cabecera `X-Trace-Id` con el id de traza de esa petición concreta.
Es el atajo para pasar de una llamada a su traza en New Relic:

```bash
curl -si $AUTH "$URL/users" | grep -i x-trace-id
# X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
```

Con ese valor, en New Relic: **Query builder** y

```sql
SELECT * FROM Span WHERE trace.id = '4bf92f3577b34da6a3ce929d0e0e4736'
```

o directamente `one.newrelic.com > APM & Services > microservice-users > Distributed tracing`
y pegar el id en el buscador.

### 3.6 Ejecución local

```bash
cp .env.example .env      # y rellena los CHANGE_ME
set -a && . ./.env && set +a
mvn spring-boot:run
curl -u "$BASIC_AUTH_USER:$BASIC_AUTH_PASSWORD" http://localhost:8080/users
```

En PowerShell:

```powershell
Get-Content .env | Where-Object { $_ -notmatch '^#' -and $_ -match '=' } |
  ForEach-Object { $k,$v = $_ -split '=',2
    [System.Environment]::SetEnvironmentVariable($k, $v) }
mvn spring-boot:run
```

Para instrumentar también en local y ver la telemetría en New Relic desde tu máquina:
`mvn package` (descarga el agente a `target/otel-javaagent.jar`) y descomenta
`JAVA_TOOL_OPTIONS` en `.env`.

La base de datos local sigue siendo la Azure SQL del PoC: hay que rellenar `SQL_SERVER`,
`SQL_SERVER_NAME` y las credenciales, y tu IP tiene que estar en el firewall del servidor
(la regla que crea el Bicep solo permite servicios de Azure).

```bash
MY_IP=$(curl -s ifconfig.me)
az sql server firewall-rule create -g rg-usersvc -s <servidor> \
  -n dev-laptop --start-ip-address "$MY_IP" --end-ip-address "$MY_IP"
```

---

## 4. Cómo ver la telemetría en New Relic

### 4.1 Qué se envía

| Señal | Contenido |
|-------|-----------|
| **Trazas** | Cada petición HTTP, con los spans hijos de JDBC (consultas a Azure SQL), incluyendo el tiempo de base de datos y la sentencia saneada |
| **Métricas** | JVM (heap, GC, hilos), HTTP server, pool de conexiones HikariCP y todas las métricas de Micrometer que expone Actuator |
| **Logs** | Todo lo que pasa por Logback, correlacionado con `trace_id` y `span_id` vía MDC, incluidos los logs internos del propio agente |

Además, `RequestLoggingFilter` añade a cada log de petición atributos estructurados que se
pueden filtrar y agrupar directamente en New Relic: `http.method`, `http.target`,
`http.status_code`, `http.duration_ms`, `http.client_ip`. Las cabeceras sensibles
(`authorization`, `cookie`, `x-api-key`...) se excluyen antes de enviarse.

### 4.2 Generar tráfico

La telemetría tarda **1-2 minutos** en aparecer. Genera algo de carga primero:

```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null $AUTH "$URL/users"
  curl -s -o /dev/null $AUTH "$URL/status"
done

# Y algo de error, para que haya algo que investigar
curl -s -o /dev/null "$URL/users"                        # 401
curl -s -o /dev/null $AUTH "$URL/users/no-existe"        # 404
```

### 4.3 Dónde mirar en la interfaz

Todo está en `one.newrelic.com`. El servicio aparece como **`microservice-users`** dentro
del namespace `poc-observability`.

| Qué quieres ver | Ruta en la interfaz |
|-----------------|---------------------|
| Throughput, latencia y tasa de error | **APM & Services** > `microservice-users` > *Summary* |
| Trazas completas, con el desglose de tiempo en base de datos | **APM & Services** > `microservice-users` > *Distributed tracing* |
| Tiempo por operación de base de datos | **APM & Services** > `microservice-users` > *Databases* |
| Métricas de JVM (heap, GC, hilos) | **APM & Services** > `microservice-users` > *JVMs*, o *Metrics explorer* |
| Cualquier métrica suelta, incluidas las de HikariCP | **Query your data** > *Metrics explorer* > filtra por `jvm.`, `hikaricp.`, `http.server.` |
| Logs, ya correlacionados con las trazas | **Logs** > filtro `service.name:"microservice-users"` |
| Todo el PoC junto (gateway + microservicios) | **APM & Services**, filtrando por `service.namespace = poc-observability` |
| Consultas a medida | **Query your data** > *Query builder* (NRQL) |

Dos atajos que se usan mucho:

- En cualquier log, el enlace **See trace** salta a la traza de esa misma petición. Funciona
  porque el agente inyecta `trace.id` y `span.id` en cada registro.
- En cualquier span, la pestaña **Logs** muestra solo los logs de esa traza.

### 4.4 Recetario NRQL

Copia y pega en **Query your data > Query builder**.

**Salud del servicio**

```sql
-- Peticiones por minuto
SELECT rate(count(*), 1 minute) FROM Span
WHERE service.name = 'microservice-users' AND span.kind = 'server'
SINCE 30 minutes ago TIMESERIES

-- Latencia p50 / p95 / p99 por endpoint
SELECT percentile(duration.ms, 50, 95, 99) FROM Span
WHERE service.name = 'microservice-users' AND span.kind = 'server'
SINCE 30 minutes ago FACET name

-- Tasa de error
SELECT percentage(count(*), WHERE otel.status_code = 'ERROR') FROM Span
WHERE service.name = 'microservice-users' SINCE 30 minutes ago TIMESERIES

-- Reparto de códigos HTTP
SELECT count(*) FROM Span
WHERE service.name = 'microservice-users' AND http.response.status_code IS NOT NULL
SINCE 30 minutes ago FACET http.response.status_code
```

**Métricas**

```sql
-- Memoria de la JVM por pool
SELECT latest(jvm.memory.used) FROM Metric
WHERE service.name = 'microservice-users' SINCE 30 minutes ago
FACET jvm.memory.pool.name TIMESERIES

-- Pool de conexiones: activas, en espera y tamaño
SELECT latest(hikaricp.connections.active), latest(hikaricp.connections.pending),
       latest(hikaricp.connections) FROM Metric
WHERE service.name = 'microservice-users' SINCE 30 minutes ago TIMESERIES

-- Qué métricas están llegando (útil para descubrir nombres)
SELECT uniques(metricName) FROM Metric
WHERE service.name = 'microservice-users' SINCE 30 minutes ago LIMIT 200
```

**Logs**

```sql
-- Últimos logs con su traza
SELECT timestamp, message, trace.id, span.id FROM Log
WHERE service.name = 'microservice-users' SINCE 30 minutes ago LIMIT 50

-- Peticiones lentas, usando los atributos del filtro
SELECT timestamp, http.method, http.target, http.status_code, http.duration_ms, trace.id
FROM Log WHERE service.name = 'microservice-users' AND http.duration_ms > 500
SINCE 30 minutes ago LIMIT 50

-- Solo errores
SELECT timestamp, message, trace.id FROM Log
WHERE service.name = 'microservice-users' AND level = 'ERROR'
SINCE 30 minutes ago LIMIT 50

-- Respuestas 5xx agrupadas por endpoint
SELECT count(*) FROM Log
WHERE service.name = 'microservice-users' AND http.status_code >= 500
SINCE 1 hour ago FACET http.target
```

**Base de datos** (más consultas en la sección 5)

```sql
-- Spans de base de datos: confirma que la instrumentación JDBC funciona
SELECT count(*), average(duration.ms) FROM Span
WHERE service.name = 'microservice-users' AND db.system IS NOT NULL
SINCE 30 minutes ago FACET name
```

**Vista de todo el PoC**

```sql
SELECT count(*) FROM Span
WHERE service.namespace = 'poc-observability' SINCE 1 hour ago FACET service.name
```

### 4.5 Variables del agente

Se aplican como app settings de la Web App. Estas son las que documenta New Relic para su
endpoint OTLP:

| Variable | Valor | Para qué |
|----------|-------|----------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://otlp.eu01.nr-data.net:4318` | Endpoint OTLP de la cuenta |
| `OTEL_EXPORTER_OTLP_HEADERS` | `api-key=<license key>` | Autenticación de ingesta |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | Único protocolo OTLP admitido por New Relic |
| `OTEL_EXPORTER_OTLP_COMPRESSION` | `gzip` | Reduce el volumen de red |
| `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` | `delta` | New Relic ingesta temporalidad delta |
| `OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION` | `base2_exponential_bucket_histogram` | Percentiles precisos con menos datos |
| `OTEL_METRIC_EXPORT_INTERVAL` | `30000` | Envío de métricas cada 30 s |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | Sin muestreo: todas las trazas |
| `OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT` | `4095` | New Relic descarta atributos más largos |
| `OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS` | `process.command_args` | Ese atributo supera el límite y no aporta valor |
| `OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED` | `true` | Reintentos ante errores transitorios |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `http` | Convenciones semánticas HTTP estables |
| `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES` | `*` | Envía `trace_id` y `span_id` como atributos del log |
| `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES` | `true` | Convierte los `addKeyValue()` de SLF4J en atributos |
| `OTEL_JAVAAGENT_LOGGING` | `application` | Los logs del agente también llegan a New Relic |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name`, `service.version`, `service.namespace`, `deployment.environment`, `cloud.provider` | Permite filtrar y agrupar en New Relic |

Con `observability_enabled=false` en el despliegue, el agente sigue adjunto pero se
autodesactiva (`OTEL_JAVAAGENT_ENABLED=false`) y los tres exporters pasan a `none`.

---

## 5. Telemetría de base de datos

Es la parte que más confusión genera, porque hay **tres cosas distintas** que se suelen llamar
igual y llegan por caminos separados.

| Qué | De dónde sale | Dónde se ve | Requiere |
|-----|---------------|-------------|----------|
| **Spans de SQL**: una consulta, su duración y su sentencia | Agente OTel, instrumentando JDBC | New Relic, `Span` | Nada, va por defecto |
| **Métricas del pool** de conexiones | Micrometer vía Actuator | New Relic, `Metric` | Nada, va por defecto |
| **Logs de SQL**: la sentencia como registro de log | Logger `org.hibernate.SQL` | New Relic, `Log` | `SQL_LOG_LEVEL=DEBUG` |
| **Logs de plataforma** del servicio SQL: errores, timeouts, deadlocks, Query Store | Azure Monitor, no la aplicación | Log Analytics y/o New Relic | Diagnostic Setting, y **que ocurra el evento** |

### 5.1 Spans de SQL (activo por defecto)

Es la vía principal para ver qué hace la aplicación contra la base de datos. Cada sentencia
cuelga del span de la petición HTTP que la provocó.

```sql
-- ¿Llegan spans de base de datos?
SELECT count(*) FROM Span
WHERE service.name = 'microservice-users' AND db.system IS NOT NULL
SINCE 30 minutes ago TIMESERIES

-- Tiempo de base de datos por sentencia
SELECT count(*), average(duration.ms), max(duration.ms) FROM Span
WHERE service.name = 'microservice-users' AND db.system IS NOT NULL
SINCE 30 minutes ago FACET db.statement

-- Esperas del pool de conexiones (spans de DataSource.getConnection)
SELECT average(duration.ms), max(duration.ms) FROM Span
WHERE service.name = 'microservice-users' AND name LIKE '%getConnection%'
SINCE 30 minutes ago TIMESERIES

-- Consultas que fallan
SELECT timestamp, db.statement, otel.status_description, trace.id FROM Span
WHERE service.name = 'microservice-users' AND db.system IS NOT NULL
  AND otel.status_code = 'ERROR' SINCE 1 hour ago LIMIT 50
```

Lo activan estas variables, ya aplicadas por el Bicep:

| Variable | Efecto |
|----------|--------|
| `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true` | Manda la sentencia con los valores literales sustituidos por `?` |
| `OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED=true` | Añade un span por `DataSource.getConnection`, que hace visibles las esperas del pool. Desactivado por defecto en el agente |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` | Publica las métricas de HikariCP y del resto de Actuator como métricas OTLP |

### 5.2 Logs de SQL (hay que activarlos)

**Por defecto no llega ni una sentencia SQL a los logs de New Relic, y es intencionado.**
Para activarlas, pon la variable de repositorio `SQL_LOG_LEVEL=DEBUG` en
`Settings > Secrets and variables > Actions > Variables` y vuelve a desplegar.

```sql
SELECT timestamp, message, trace.id FROM Log
WHERE service.name = 'microservice-users' AND message LIKE '%select%'
SINCE 30 minutes ago LIMIT 50
```

Con `SQL_BIND_LOG_LEVEL=TRACE` se añaden además los valores de los parámetros, que son los
datos reales enviados a la base de datos. Úsalo solo para depurar y quítalo después.

Devuélvelo a `INFO` en cuanto termines: sube bastante el volumen de logs y con la cuota diaria
de 1 GB del workspace es fácil llegar al tope.

> **Detalle que importa si tocas esto.** El nivel se declara en `application.yaml` bajo
> `logging.level` como `org.hibernate.SQL: ${SQL_LOG_LEVEL:INFO}`, y **no** como un app setting
> `LOGGING_LEVEL_ORG_HIBERNATE_SQL`. El relaxed binding de Spring Boot pasa a minúsculas los
> nombres de las variables de entorno, y `org.hibernate.sql` es un logger distinto de
> `org.hibernate.SQL`: por esa vía el nivel se aplica a un logger que nadie usa y no aparece
> ninguna sentencia.
>
> Por el mismo motivo `spring.jpa.show-sql` está en `false`: escribe directamente en
> `System.out`, que el agente no instrumenta, así que esas líneas se quedan en la consola del
> contenedor y nunca llegan a New Relic.

### 5.3 Logs de plataforma del servicio SQL

Los genera Azure, no la aplicación. Cubren `Errors`, `Timeouts`, `Blocks`, `Deadlocks`,
`QueryStoreRuntimeStatistics`, `QueryStoreWaitStatistics`, `DatabaseWaitStatistics`,
`SQLInsights` y `AutomaticTuning`, y van al workspace por el Diagnostic Setting
`diag-sqldb-users`.

**Una consulta que funciona no genera ninguno de esos logs.** Son categorías de eventos
excepcionales: si no hay errores, ni timeouts, ni bloqueos, no hay nada que registrar y la
tabla `AzureDiagnostics` sale vacía por más peticiones que hagas. Lo único que llega con
tráfico normal es:

- **`QueryStoreRuntimeStatistics`**, que se emite por ventana de agregación del Query Store.
  En Azure SQL esa ventana es de **60 minutos** por defecto, así que en un PoC corto puede no
  llegar nada.
- Las **métricas** (`AzureMetrics`): DTU consumidas, conexiones, almacenamiento. Estas sí
  fluyen en minutos.

Para ver el consumo de la base de datos con tráfico normal, mira las métricas, no los logs:

```bash
WS=$(az monitor log-analytics workspace show -g rg-usersvc -n log-usersvc --query customerId -o tsv)

az monitor log-analytics query --workspace "$WS" --analytics-query "
AzureMetrics
| where ResourceProvider == 'MICROSOFT.SQL'
| where MetricName in ('dtu_consumption_percent','connection_successful','storage_percent')
| project TimeGenerated, MetricName, Average, Maximum
| order by TimeGenerated desc | take 50"
```

Si quieres provocar un log de la categoría `Errors` para comprobar que el camino funciona,
tiene que ser un error **del motor**, no de la aplicación: un `404` de `/users/{id}` no sirve,
porque eso lo resuelve la aplicación sin llegar a fallar ninguna sentencia. Lo más rápido es
un login fallido:

```bash
sqlcmd -S <servidor>.database.windows.net -d sqldb-users -U usuario_que_no_existe -P loquesea
```

Un minuto después debería aparecer con `Category == 'Errors'`:

```bash
az monitor log-analytics query --workspace "$WS" --analytics-query "
AzureDiagnostics
| where ResourceProvider == 'MICROSOFT.SQL' and Category == 'Errors'
| project TimeGenerated, Message
| order by TimeGenerated desc | take 20"
```

---

## 6. Azure Monitor y Log Analytics

Se crean dos Diagnostic Settings hacia el mismo workspace:

| Origen | Categorías | Tablas |
|--------|-----------|--------|
| Web App | `allLogs` (HTTP, consola, aplicación, plataforma, auditoría, IPSec) + `AllMetrics` | `AppServiceHTTPLogs`, `AppServiceConsoleLogs`, `AppServiceAppLogs`, `AppServicePlatformLogs`, `AppServiceAuditLogs` |
| SQL Database | `allLogs` (errores, timeouts, bloqueos, deadlocks, Query Store) + métricas `Basic` | `AzureDiagnostics`, `AzureMetrics` |

Además se habilita el logging a sistema de ficheros de la Web App (`applicationLogs` nivel
`Information` y `httpLogs`), requisito para que esas categorías lleguen al workspace.

### 6.1 Consultas KQL

```bash
WS=$(az monitor log-analytics workspace show -g rg-usersvc -n log-usersvc --query customerId -o tsv)

# Peticiones HTTP atendidas
az monitor log-analytics query --workspace "$WS" --analytics-query "
AppServiceHTTPLogs
| project TimeGenerated, CsMethod, CsUriStem, ScStatus, TimeTaken
| order by TimeGenerated desc | take 50"

# Salida de consola de la aplicacion (incluye el arranque de Spring Boot y del agente)
az monitor log-analytics query --workspace "$WS" --analytics-query "
AppServiceConsoleLogs | project TimeGenerated, ResultDescription
| order by TimeGenerated desc | take 50"

# Errores y esperas de la base de datos
az monitor log-analytics query --workspace "$WS" --analytics-query "
AzureDiagnostics
| where ResourceProvider == 'MICROSOFT.SQL'
| project TimeGenerated, Category, OperationName, resource_s
| order by TimeGenerated desc | take 50"

# Comprobar que los diagnostic settings existen
az monitor diagnostic-settings list \
  --resource "$(az webapp show -g rg-usersvc -n <webapp> --query id -o tsv)" -o table
```

### 6.2 Logs en vivo del contenedor

Es lo primero que hay que mirar cuando la aplicación no arranca o el agente no exporta:

```bash
az webapp log tail --resource-group rg-usersvc --name <webapp>
```

### 6.3 Correlacionar Azure Monitor con New Relic

Los logs de plataforma están en Log Analytics y los de aplicación en New Relic. Para saltar de
uno a otro: los `AppServiceHTTPLogs` traen `CsUriStem`, `ScStatus` y marca de tiempo, y los
logs de New Relic traen `http.target`, `http.status_code` y `trace.id`. Buscando por ruta y
minuto se llega al mismo evento en los dos sistemas.

Para una vista realmente unificada, la integración de la sección 10 lleva los logs y métricas
de plataforma a New Relic y deja de hacer falta el salto manual.

---

## 7. Si no llega telemetría

Recorre esta lista en orden; está ordenada por probabilidad.

| Síntoma | Causa habitual | Comprobación |
|---------|----------------|--------------|
| No llega **nada** a New Relic | License key de otra región. Una key europea contra el endpoint de EE. UU. devuelve `403` y no se ingesta nada | Que `NR_OTLP_ENDPOINT` case con la región de la cuenta: EU `https://otlp.eu01.nr-data.net:4318`, US `https://otlp.nr-data.net:4318` |
| No llega **nada** a New Relic | Se ha usado una *User API key* en lugar de una *license key* de ingesta | New Relic > Administration > API keys, tipo `INGEST - LICENSE` |
| No llega **nada** a New Relic | `NR_OTLP_ENDPOINT` sin `https://` | Revisar la variable |
| No llega **nada** a New Relic | Se desplegó con `observability_enabled=false` | `az webapp config appsettings list` y buscar `OTEL_JAVAAGENT_ENABLED` |
| Llegan trazas pero **no logs** | El agente no está adjunto o Logback no está instrumentado | `az webapp log tail` y buscar los errores del agente al arrancar |
| Llegan logs pero **no sentencias SQL** | `SQL_LOG_LEVEL` sigue en `INFO`, que es el valor por defecto | Ponerla en `DEBUG` y redesplegar. Ver [5.2](#52-logs-de-sql-hay-que-activarlos) |
| No hay **logs de BD en Log Analytics** | Las consultas correctas no generan logs de plataforma; solo lo hacen los errores, timeouts, bloqueos y el Query Store cada 60 min | Mirar `AzureMetrics` en vez de `AzureDiagnostics`. Ver [5.3](#53-logs-de-plataforma-del-servicio-sql) |
| No hay **logs de BD en New Relic** | El Diagnostic Setting hacia New Relic tarda en crearse tras el despliegue | `az monitor diagnostic-settings list --resource <id de la BD>`. Ver [10.3](#103-cuánto-tarda-en-empezar-a-fluir) |
| Los datos llegan **con retraso** | Normal: 1-2 min para trazas y logs, hasta 30 s de intervalo de exportación para métricas | Esperar y refrescar |
| Un cambio de variable **no se refleja** | La aplicación no ha releído los settings | El pipeline reinicia la app y verifica los settings; revisar el paso *Verify the effective application settings* |

El agente escribe sus errores de exportación en la consola del contenedor al arrancar, así que
`az webapp log tail` es donde antes se ve un `403` o un endpoint mal formado.

---

## 8. Configuración previa

Todo lo de esta sección se hace **una sola vez**. Después, cada despliegue es automático.

### 8.1 Secrets de GitHub

`Settings > Secrets and variables > Actions > Secrets > New repository secret`

| Secret | Contenido | Cómo obtenerlo |
|--------|-----------|----------------|
| `AZURE_CLIENT_ID` | Application (client) ID de la app de Entra ID | salida del paso 8.3 |
| `AZURE_TENANT_ID` | Directory (tenant) ID | `az account show --query tenantId -o tsv` |
| `AZURE_SUBSCRIPTION_ID` | Id de la suscripción | `az account show --query id -o tsv` |
| `SQL_ADMIN_USER` | Login del administrador de SQL. **No puede ser** `admin`, `administrator`, `sa`, `root`, `dbmanager` ni `loginmanager` | por ejemplo `sqladminpoc` |
| `SQL_ADMIN_PASSWORD` | Contraseña del administrador de SQL. Mínimo 8 caracteres con 3 de estas 4 categorías: mayúscula, minúscula, dígito, símbolo | `openssl rand -base64 24` |
| `BASIC_AUTH_USER` | Usuario Basic Auth que acepta la API | debe coincidir con `UPSTREAM_USERS_BASIC_USER` en el repo del gateway |
| `BASIC_AUTH_PASSWORD` | Contraseña Basic Auth que acepta la API | `openssl rand -hex 24` |
| `NR_LICENSE_KEY` | License key de **ingesta** de New Relic (no una User API key) | New Relic > Administration > API keys > tipo `INGEST - LICENSE` |
| `GH_ADMIN_TOKEN` | PAT con escritura sobre *Environments* y *Secrets*. **Solo** lo usa el workflow `newrelic-azure-integration` | GitHub > Settings > Developer settings > Personal access tokens |
| `NR_ACCOUNT_ID` / `NR_ORGANIZATION_ID` / `NR_USER_EMAIL` | Identificadores de la cuenta de New Relic. **Solo** los usa el workflow `newrelic-native-integration` | one.newrelic.com > Administration |

El workflow verifica que los cinco secretos funcionales existen y falla en el primer paso si
falta alguno, antes de crear nada en Azure.

### 8.2 Variables de GitHub

`Settings > Secrets and variables > Actions > Variables`. Todas son opcionales: si no se
definen se usa el valor por defecto.

| Variable | Descripción | Por defecto |
|----------|-------------|-------------|
| `AZURE_LOCATION` | Región de Azure | `westeurope` |
| `AZURE_RESOURCE_GROUP` | Resource group del PoC | `rg-usersvc` |
| `POC_NAME_PREFIX` | Prefijo de nombres, 3-12 caracteres | `usersvc` |
| `POC_OWNER` | Tag `owner` para control de coste | `unknown` |
| `POC_TTL` | Tag `ttl` | `1h` |
| `APP_SERVICE_SKU` | `F1`, `B1` o `B2`. Con `F1` la plantilla desactiva Always On y el health check, que ese plan no soporta | `B1` |
| `SQL_SKU_NAME` | `Basic`, `S0` o `GP_S_Gen5_1` (serverless) | `Basic` |
| `SQL_DATABASE_NAME` | Nombre de la base de datos | `sqldb-users` |
| `NR_OTLP_ENDPOINT` | Endpoint OTLP. EU: `https://otlp.eu01.nr-data.net:4318`, US: `https://otlp.nr-data.net:4318` | `https://otlp.eu01.nr-data.net:4318` |
| `OTEL_SERVICE_NAME` | Nombre del servicio en New Relic | `microservice-users` |
| `ENVIRONMENT` | Atributo `deployment.environment` | `poc` |
| `SERVICE_NAMESPACE` | Atributo `service.namespace`, común a todo el PoC | `poc-observability` |
| `LOG_LEVEL` | Nivel de log de la aplicación | `INFO` |
| `SQL_LOG_LEVEL` | Nivel del logger `org.hibernate.SQL`. `DEBUG` envía cada sentencia a New Relic como log | `INFO` |
| `LOG_RETENTION_DAYS` | Retención de Log Analytics | `30` |
| `LOG_DAILY_QUOTA_GB` | Tope diario de ingesta | `1` |
| `ENABLE_LOG_ANALYTICS` | Enviar los Diagnostic Settings a Log Analytics. Ponlo a `false` cuando el servicio nativo de New Relic ya reenvíe los logs, para no ingerir el mismo dato dos veces | `true` |
| `ENABLE_ACTIVITY_LOG_EXPORT` | Exportar el Activity Log de la suscripción | `false` |
| `ENABLE_SCHEDULED_CLEANUP` | Activa la limpieza horaria programada | desactivada |
| `POC_MAX_AGE_HOURS` | Edad máxima antes del borrado programado | `2` |

> **`NR_OTLP_ENDPOINT` debe corresponder a la región de tu cuenta de New Relic.** Una license
> key europea contra el endpoint de EE. UU. devuelve `403` y no se ingesta nada.

### 8.3 Aplicación de Entra ID con credenciales federadas (OIDC)

No se crea ni se almacena ningún client secret: GitHub presenta un token OIDC de corta
duración que Azure valida contra la credencial federada.

```bash
az login
SUB_ID=$(az account show --query id -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)

# No escribas el repositorio a mano: derivalo. Tiene que ser exactamente el
# "owner/repo" que GitHub tiene registrado, respetando mayusculas y minusculas.
GH_REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
# Sin la CLI de GitHub, sacalo del remoto de git:
#   GH_REPO=$(git remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')
echo "GH_REPO = $GH_REPO"

# 1. Aplicacion y service principal
APP_ID=$(az ad app create --display-name "gh-poc-microservice-users" --query appId -o tsv)
az ad sp create --id "$APP_ID"
SP_ID=$(az ad sp show --id "$APP_ID" --query id -o tsv)

echo "AZURE_CLIENT_ID       = $APP_ID"
echo "AZURE_TENANT_ID       = $TENANT_ID"
echo "AZURE_SUBSCRIPTION_ID = $SUB_ID"
```

### 8.4 Credenciales federadas

El `subject` **no lo eliges tú**: es el claim `sub` que GitHub mete en el token, y Azure lo
compara carácter a carácter. Si no coincide, la acción `azure/login` falla con
`AADSTS70021: No matching federated identity record found`.

Plantillas por defecto del claim `sub` según el disparador:

| Cómo se ejecuta el workflow | Valor de `sub` |
|-----------------------------|----------------|
| `push` o `workflow_dispatch` sobre una rama | `repo:OWNER/REPO:ref:refs/heads/NOMBRE_RAMA` |
| Pull request | `repo:OWNER/REPO:pull_request` |
| Con GitHub Environment | `repo:OWNER/REPO:environment:NOMBRE_ENTORNO` |
| Tag | `repo:OWNER/REPO:ref:refs/tags/NOMBRE_TAG` |

> **No hace falta adivinarlo.** El workflow `deploy` imprime el `sub` real de cada ejecución
> en el paso *Show the OIDC subject expected by Azure* y lo deja en el resumen del run, antes
> de intentar el login. Si el login falla, copia ese valor literal al campo `subject` de la
> credencial federada. Ese paso funciona aunque no tengas nada configurado en Azure todavía,
> así que puedes lanzar el workflow una vez a propósito solo para leer el valor.

```bash
# Ejecuciones sobre la rama main (push y workflow_dispatch sobre main)
az ad app federated-credential create --id "$APP_ID" --parameters "{
  \"name\": \"gh-main\",
  \"issuer\": \"https://token.actions.githubusercontent.com\",
  \"subject\": \"repo:${GH_REPO}:ref:refs/heads/main\",
  \"audiences\": [\"api://AzureADTokenExchange\"]
}"

# Opcional: pull requests
az ad app federated-credential create --id "$APP_ID" --parameters "{
  \"name\": \"gh-pr\",
  \"issuer\": \"https://token.actions.githubusercontent.com\",
  \"subject\": \"repo:${GH_REPO}:pull_request\",
  \"audiences\": [\"api://AzureADTokenExchange\"]
}"
```

Comprueba que ha quedado bien registrado:

```bash
az ad app federated-credential list --id "$APP_ID" \
  --query "[].{name:name,subject:subject}" -o table
```

#### Si renombras el repositorio o la organización

El `sub` por defecto lleva el nombre del repositorio, así que renombrarlo **rompe el login**
hasta que recrees la credencial. GitHub permite cambiar la plantilla del claim para usar el
**id numérico del repositorio**, que es inmutable:

```bash
# Cambia la plantilla del claim en el repositorio
gh api -X PUT "repos/${GH_REPO}/actions/oidc/customization/sub" \
  -f use_default=false -f 'include_claim_keys[]=repository_id' -f 'include_claim_keys[]=ref'

# Con esa plantilla, el subject pasa a ser repository_id:<id>:ref:refs/heads/main
REPO_ID=$(gh api "repos/${GH_REPO}" -q .id)
az ad app federated-credential create --id "$APP_ID" --parameters "{
  \"name\": \"gh-main-by-id\",
  \"issuer\": \"https://token.actions.githubusercontent.com\",
  \"subject\": \"repository_id:${REPO_ID}:ref:refs/heads/main\",
  \"audiences\": [\"api://AzureADTokenExchange\"]
}"
```

Tras cambiar la plantilla, vuelve a lanzar el workflow y **lee el `sub` que imprime**: es la
verificación definitiva de qué está mandando GitHub.

### 8.5 Permisos en Azure

El workflow crea el **resource group**, por lo que necesita permisos a nivel de suscripción.
Con `Contributor` es suficiente: esta plantilla no crea asignaciones de rol, así que no hace
falta `Owner` ni `Role Based Access Control Administrator`.

```bash
az role assignment create \
  --assignee-object-id "$SP_ID" \
  --assignee-principal-type ServicePrincipal \
  --role "Contributor" \
  --scope "/subscriptions/$SUB_ID"
```

Si tu organización no permite dar Contributor a nivel de suscripción, crea el grupo a mano y
da permisos solo sobre él:

```bash
az group create --name rg-usersvc --location westeurope \
  --tags environment=poc ttl=1h project=poc-microservice-users

az role assignment create \
  --assignee-object-id "$SP_ID" --assignee-principal-type ServicePrincipal \
  --role "Contributor" --scope "/subscriptions/$SUB_ID/resourceGroups/rg-usersvc"
```

En ese caso el despliegue a nivel de suscripción sigue fallando al crear el grupo: hay que
cambiar `infra/main.bicep` a `targetScope = 'resourceGroup'`, eliminar el recurso
`Microsoft.Resources/resourceGroups` y usar `az deployment group create`.

### 8.6 Proveedores de recursos

```bash
for ns in Microsoft.Web Microsoft.Sql Microsoft.OperationalInsights Microsoft.Insights; do
  az provider register --namespace "$ns" --wait
  az provider show --namespace "$ns" --query "{ns:namespace,state:registrationState}" -o tsv
done
```

### 8.7 Resumen

| Paso | Dónde | Resultado |
|------|-------|-----------|
| Crear app de Entra ID + SP | Azure | `AZURE_CLIENT_ID` |
| Crear credencial federada `repo:ORG/REPO:ref:refs/heads/main` | Azure | GitHub puede autenticarse sin secreto |
| Asignar `Contributor` en la suscripción | Azure | El pipeline puede crear el RG y los recursos |
| Registrar proveedores `Web`, `Sql`, `OperationalInsights`, `Insights` | Azure | Los recursos se pueden crear |
| Crear los secrets | GitHub | El pipeline tiene las credenciales |
| Crear las variables que quieras cambiar | GitHub | Ajuste de región, SKU y nombres |

---

## 9. Despliegue

### 9.1 Desde GitHub Actions

- **`push` a `main`**: construye y despliega. **No activa la auto-destrucción.**
- **Manual** (Actions > `deploy` > `Run workflow`):

| Input | Descripción | Por defecto |
|-------|-------------|-------------|
| `auto_destroy_minutes` | Minutos hasta borrar el resource group. `0` lo desactiva | `60` |
| `observability_enabled` | Adjuntar el agente OTel y exportar a New Relic | `true` |

Qué hace el pipeline:

1. Comprueba y enmascara los secretos (`::add-mask::`).
2. Compila con Maven (`mvn -B -ntp clean package`), que además descarga el agente OTel a
   `target/otel-javaagent.jar`.
3. Empaqueta `app.jar` + `otel-javaagent.jar` en `app.zip`. El jar se renombra a `app.jar`
   porque es el nombre que arranca la imagen Java SE de App Service.
4. Login en Azure con OIDC.
5. Despliega la infraestructura con `az deployment sub create` e `infra/main.bicepparam`.
   Los secretos viajan como variables de entorno, nunca como argumentos de línea de comandos.
6. Lee de vuelta los app settings y **falla** si alguno no coincide con lo desplegado
   (paso *Verify the effective application settings*).
7. Sube el paquete con `az webapp deploy --type zip --clean true --restart true` y reinicia la
   app, para que relea los valores aunque el paquete no haya cambiado.
8. Smoke tests sobre HTTPS:
   - `/actuator/health` debe devolver `"status":"UP"` (reintenta hasta 10 minutos por el
     arranque en frío de Spring Boot con agente).
   - La cadena de certificado se valida sin `--insecure`.
   - HTTP en claro debe redirigir, no servir contenido.
   - `/users` sin credenciales debe devolver `401`.
   - `/users` **con** credenciales debe devolver `200`, lo que prueba que la conexión a
     Azure SQL funciona de extremo a extremo.
9. Job `auto-destroy`: espera el TTL y borra el resource group.

Cambiar una variable o un secreto en GitHub y relanzar `deploy` **basta** para que el App
Service lo recoja: los app settings se aplican como recurso hijo
`Microsoft.Web/sites/config@appsettings`, que reemplaza la colección entera en cada despliegue.

### 9.2 Desde tu máquina

```bash
az login
export AZURE_LOCATION=westeurope AZURE_RESOURCE_GROUP=rg-usersvc POC_NAME_PREFIX=usersvc
export SQL_ADMIN_USER=... SQL_ADMIN_PASSWORD=... BASIC_AUTH_USER=... BASIC_AUTH_PASSWORD=...
export NR_LICENSE_KEY=...

# El fichero .bicepparam declara su plantilla con "using": no se pasa --template-file
az deployment sub create --location "$AZURE_LOCATION" --parameters infra/main.bicepparam

mvn -B clean package
mkdir -p dist && cp target/microservice-users-1.0.0.jar dist/app.jar \
  && cp target/otel-javaagent.jar dist/ && (cd dist && zip -r ../app.zip .)

az webapp deploy --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "<nombre-del-webapp>" --src-path app.zip --type zip
```

`main.bicepparam` lee los valores con `readEnvironmentVariable` y todos declaran un valor por
defecto, así que **exporta las variables antes de lanzar `az deployment`**: si olvidas alguna,
el error llega de Azure (por ejemplo, contraseña de administrador de SQL vacía) en lugar de
llegar de la validación local. La comprobación de presencia vive en `deploy.yml`, que dice
exactamente qué secreto falta.

---

## 10. Integración de Azure con New Relic

Sirve para llevar a New Relic los datos que la aplicación no puede ver: métricas y logs de
plataforma del servicio SQL y del App Service, más el Activity Log de la suscripción.

### 10.1 Servicio nativo (vía implementada)

El workflow **`newrelic-native-integration`** (manual, se ejecuta **una sola vez por
suscripción**) despliega `infra/newrelic.bicep`, que crea:

- un resource group aparte, `rg-newrelic-shared`, que **sobrevive al destroy del PoC**;
- el recurso `NewRelic.Observability/monitors` vinculado a la cuenta de New Relic existente
  (se vincula, no se crea: no aparece un recurso SaaS de Marketplace y la facturación de New
  Relic no cambia de sitio);
- sus `tagRules`: métricas de toda la suscripción, logs de recurso y Activity Log, con la
  etiqueta `newrelicLogs=exclude` como regla de exclusión.

A partir de ahí, Azure pone y quita por sí mismo los Diagnostic Settings hacia New Relic en
cada recurso nuevo que encaje en las reglas. No hace falta Event Hub, ni Storage Account, ni
Function App, ni app de Entra ID: **0 EUR de recursos de reenvío**.

Secrets que necesita:

| Secret | Dónde se saca |
|--------|---------------|
| `NR_ACCOUNT_ID` | one.newrelic.com > Administration > Access management > Accounts |
| `NR_ORGANIZATION_ID` | one.newrelic.com > Administration > Organization |
| `NR_USER_EMAIL` | Email del propietario de la cuenta; el resource provider lo exige |
| `NR_LICENSE_KEY` | El mismo ingest key que ya usa la aplicación |

Y la variable `NR_REGION` (`eu` o `us`), que debe coincidir con la región de la cuenta.

### 10.2 Después de ejecutarlo

1. Pon la variable `ENABLE_LOG_ANALYTICS=false` en los repositorios de microservicios y vuelve
   a desplegar. Así los logs de plataforma dejan de ir también al workspace y no se pagan dos
   veces. El workspace se sigue creando: uno sin ingesta no cuesta nada.
2. Comprueba en el portal, sobre el servidor SQL: **Diagnostic settings** debe mostrar una
   entrada hacia New Relic creada por Azure.
3. En la web app **no** debe aparecer esa entrada: está excluida a propósito con la etiqueta
   `newrelicLogs=exclude`, porque el agente OTel ya manda esos logs y llegarían dos veces. Sus
   **métricas** de plataforma sí se recogen, que esas el agente no las ve.

El monitor se despliega **solo desde `poc-microservice-users`**, y el workflow aborta si
detecta más de uno en la suscripción.

### 10.3 Cuánto tarda en empezar a fluir

La creación del Diagnostic Setting sobre un recurso nuevo **puede tardar hasta una hora**, y
eso pasa en cada despliegue: cada `deploy` crea un servidor SQL y una base de datos con
nombres nuevos. La comprobación es binaria:

```bash
SQL_ID=$(az sql db show -g rg-usersvc -s <servidor> -n sqldb-users --query id -o tsv)
az monitor diagnostic-settings list --resource "$SQL_ID" -o table
```

En cuanto aparezca la entrada hacia New Relic, los logs de plataforma están fluyendo. Ese
retraso **no afecta** a las métricas, que las recoge el resource provider por su cuenta, ni a
la telemetría OTLP de la aplicación, que llega desde el primer segundo.

**Implicación práctica:** el patrón "creo el resource group y lo destruyo en 60 minutos" es el
peor caso posible para los logs de plataforma. Despliega con `auto_destroy_minutes = 0`,
espera a que aparezca el Diagnostic Setting y a partir de ahí redespliega solo la aplicación.

### 10.4 Integración por polling (alternativa)

El workflow **`newrelic-azure-integration`** (manual) trae **solo métricas** de plataforma por
polling, con una app de Entra ID y un client secret. Es idempotente: si la aplicación ya existe
la reutiliza, y los roles ya asignados no se vuelven a asignar.

Es **excluyente** con la integración nativa: usa una u otra, nunca las dos, o las métricas se
ingestan por duplicado.

Qué hace:

1. Registra el proveedor `microsoft.insights` en la suscripción si no lo estaba.
2. Busca una aplicación de Entra ID con el nombre indicado (por defecto
   `NewRelic-Integrations`). Si existe, la reutiliza; si no, la crea.
3. Se asegura de que tiene service principal.
4. Le asigna **`Reader`** y **`Monitoring Reader`** a nivel de suscripción. New Relic pide los
   dos.
5. Crea un client secret solo si la aplicación no tenía ninguno, o con `rotate_secret=true`.
6. Guarda `NR_AZURE_CLIENT_ID`, `NR_AZURE_TENANT_ID`, `NR_AZURE_SUBSCRIPTION_ID` y
   `NR_AZURE_CLIENT_SECRET` como secretos del GitHub Environment indicado.
7. Escribe en el resumen los cuatro valores no sensibles y el último paso manual: pegarlos en
   `one.newrelic.com > Infrastructure > Azure > Add an Azure account`.

| Input | Descripción | Por defecto |
|-------|-------------|-------------|
| `app_display_name` | Nombre de la aplicación de Entra ID compartida | `NewRelic-Integrations` |
| `environment_name` | GitHub Environment donde se guardan los valores | `newrelic` |
| `rotate_secret` | Crear un client secret nuevo aunque ya haya uno | `false` |
| `secret_years` | Vigencia del secreto, 1 o 2 años | `1` |
| `store_in_github` | Guardar los valores como secretos de GitHub | `true` |

Necesita **dos permisos que `deploy.yml` no necesita**, porque toca el directorio y el RBAC:

| Permiso | Para qué | Cómo darlo |
|---------|----------|------------|
| Rol de directorio **Application Developer** (o permiso Graph `Application.ReadWrite.All`) | Crear la aplicación de Entra ID | `az rest --method POST --url "https://graph.microsoft.com/v1.0/directoryRoles/roleTemplateId=cf1c38e5-3621-4004-a7cb-879624dced7c/members/$ref" --body "{\"@odata.id\":\"https://graph.microsoft.com/v1.0/directoryObjects/<OBJECT_ID_DEL_SP>\"}"` |
| **Owner** o **Role Based Access Control Administrator** en la suscripción | Asignar `Reader` y `Monitoring Reader` | `az role assignment create --assignee-object-id <OBJECT_ID_DEL_SP> --assignee-principal-type ServicePrincipal --role "Role Based Access Control Administrator" --scope "/subscriptions/$SUB_ID"` |

Si no puedes conceder esos permisos, ejecútalo con `store_in_github=false` y el resumen dirá
qué falta por hacer a mano.

También necesita el secret `GH_ADMIN_TOKEN`: `GITHUB_TOKEN` no puede escribir secretos ni crear
environments. El workflow lo comprueba **antes** de tocar Azure, para no crear un client secret
y perderlo. Si el repositorio es privado y la cuenta está en plan Free, la API de environments
falla y los valores se guardan como secretos de repositorio.

El client secret caduca (1 o 2 años). Antes de esa fecha, relanza el workflow con
`rotate_secret=true` y actualiza el valor en la UI de New Relic. La rotación usa `--append`,
así que el secreto anterior sigue válido hasta que caduque y la integración no se corta.

> Si además decides que los despliegues pasen por un GitHub Environment (añadiendo
> `environment: poc` al job de `deploy.yml`), **cambia el claim `sub` del token OIDC**: pasa de
> `repo:OWNER/REPO:ref:refs/heads/main` a `repo:OWNER/REPO:environment:poc`, y hay que crear
> una credencial federada adicional con ese subject.

---

## 11. Variables de entorno de la aplicación

Ver [`.env.example`](.env.example) para el fichero completo. Resumen de lo funcional:

| Variable | Descripción | Origen en Azure |
|----------|-------------|-----------------|
| `PORT` | Puerto de escucha | app setting, fijo a `8080` |
| `LOG_LEVEL` | Nivel de log raíz | variable `LOG_LEVEL` |
| `SQL_LOG_LEVEL` | Nivel del logger `org.hibernate.SQL` | variable `SQL_LOG_LEVEL` |
| `SQL_BIND_LOG_LEVEL` | Nivel del logger de parámetros de bind. Solo local | no se aplica en Azure |
| `ENVIRONMENT` | `deployment.environment` | variable `ENVIRONMENT` |
| `SQL_SERVER` | FQDN del servidor SQL | salida del Bicep |
| `SQL_SERVER_NAME` | Nombre corto del servidor, necesario para el login `user@server` | salida del Bicep |
| `SQL_DATABASE` | Nombre de la base de datos | variable `SQL_DATABASE_NAME` |
| `SQL_USERNAME` / `SQL_PASSWORD` | Credenciales de SQL | secrets `SQL_ADMIN_USER` / `SQL_ADMIN_PASSWORD` |
| `BASIC_AUTH_USER` / `BASIC_AUTH_PASSWORD` | Credenciales que exige la API | secrets homónimos |
| `OTEL_*` | Configuración del agente | ver sección 4.5 |

---

## 12. Coste estimado

Tarifas orientativas de West Europe. Consulta la calculadora oficial para los precios vigentes
de tu suscripción.

| Recurso | Configuración | Coste 1 hora | Coste 1 mes |
|---------|---------------|--------------|-------------|
| App Service Plan | Linux **B1** (1 vCPU, 1,75 GB) | ~0,018 EUR | ~12,90 EUR |
| Azure SQL Database | **Basic** 5 DTU, 2 GB | ~0,006 EUR | ~4,20 EUR |
| Log Analytics | 20-100 MB de ingesta | ~0,05-0,25 EUR | según uso |
| Salida de datos | Por debajo de la franquicia gratuita | ~0,00 EUR | ~0,00 EUR |
| **Total PoC de 1 hora** | | **por debajo de 0,30 EUR** | |

Se pueden bajar con las variables `APP_SERVICE_SKU` y `SQL_SKU_NAME` (sección 8.2). Ten en
cuenta que `F1` tiene 60 minutos de CPU al día, 1 GB de RAM compartida y no soporta Always On
ni health check, así que los arranques en frío hacen fallar los smoke tests.

---

## 13. Limpieza de recursos

### Manual

Actions > `destroy` > `Run workflow`, escribiendo `DESTROY` en el input de confirmación.
Borra el Diagnostic Setting de suscripción (si se creó) y después el resource group entero,
incluidos el servidor SQL y la base de datos.

### Automática

- **`auto-destroy` de `deploy.yml`**: espera `auto_destroy_minutes` (60 por defecto) y borra
  el grupo. Solo en ejecuciones manuales del workflow. Cancelar la ejecución cancela la
  limpieza.
- **Limpieza programada de `destroy.yml`**: cada hora, si `ENABLE_SCHEDULED_CLEANUP=true`,
  borra los resource groups con `project=poc-microservice-users` y `environment=poc` cuyo tag
  `createdAt` supere `POC_MAX_AGE_HOURS`.

### CLI

```bash
az group delete --name rg-usersvc --yes
az monitor diagnostic-settings subscription delete --name diag-activitylog-usersvc --yes
```

> **Advertencia de coste.** Si no destruyes el PoC, el App Service Plan B1 y la base de datos
> facturan de forma continua aunque no haya tráfico: del orden de **17 EUR al mes**. El
> borrado del resource group elimina también la base de datos y **sus backups**.

`rg-newrelic-shared`, el del monitor nativo, **no** se borra con el PoC: es compartido y
sobrevive a propósito.

---

## 14. Seguridad

| Aspecto | Estado |
|---------|--------|
| Credenciales en el repositorio | Ninguna. `.env.example` solo tiene placeholders `CHANGE_ME_*` y `.gitignore` cubre `.env*`, `*.pem`, `*.key`, `*.pfx` |
| Autenticación del pipeline | OIDC federado, sin client secret almacenado |
| Secretos en logs | Enmascarados con `::add-mask::` antes de usarse; el pipeline falla si falta alguno |
| Secretos hacia Bicep | Como variables de entorno leídas por `.bicepparam`, nunca como argumentos de línea de comandos; los parámetros son `@secure()` y no aparecen en el historial de despliegues |
| Tráfico entrante | `httpsOnly: true`, TLS mínimo 1.2, FTPS deshabilitado |
| Tráfico a la base de datos | TLS mínimo 1.2 en el servidor y `encrypt=true` con validación de certificado en la URL JDBC |
| Exposición de la base de datos | Firewall solo con la regla de servicios de Azure (`0.0.0.0`), sin acceso desde internet |
| Autorización de la API | Basic Auth sobre todos los endpoints salvo `/actuator/*` |
| Cabeceras sensibles en telemetría | `RequestLoggingFilter` excluye `authorization`, `cookie`, `set-cookie`, `proxy-authorization`, `x-api-key`, `x-auth-token` y `x-forwarded-authorization` antes de enviar atributos |
| Identidad de la aplicación | Identidad administrada de sistema activada, lista para Key Vault o acceso passwordless a SQL |

### Limitaciones conocidas

Son aceptables en un PoC y no lo serían en producción:

1. La contraseña de SQL se guarda como **app setting** de la Web App, legible por cualquiera
   con permisos de lectura del recurso. Alternativa: Key Vault con
   `@Microsoft.KeyVault(SecretUri=...)`, o `authentication=ActiveDirectoryMSI` en la URL JDBC.
2. `/actuator/health` es **público y con `show-details: always`**, así que expone el estado de
   la base de datos a cualquiera. En producción, `when-authorized`.
3. La regla de firewall de servicios de Azure permite conexiones **desde cualquier suscripción
   de Azure**, no solo la tuya. Lo correcto sería VNet + private endpoint, que en App Service
   requiere plan Standard o superior.
4. `ddl-auto: update` deja que Hibernate modifique el esquema en caliente.

### Rotación de secretos

| Secreto | Cómo rotarlo |
|---------|--------------|
| `SQL_ADMIN_PASSWORD` | `az sql server update -g rg-usersvc -n <server> --admin-password <nueva>`, actualizar el GitHub Secret y relanzar `deploy` |
| `BASIC_AUTH_*` | Actualizar el secret, relanzar `deploy` y actualizar en paralelo `UPSTREAM_USERS_BASIC_*` en el repositorio del gateway |
| `NR_LICENSE_KEY` | Crear una key nueva en New Relic, actualizar el secret, relanzar `deploy` y borrar la antigua |
| Credencial federada OIDC | `az ad app federated-credential delete` y volver a crearla. No hay secreto que rotar |

---

## 15. Referencias

- [Configurar una app Java en App Service](https://learn.microsoft.com/azure/app-service/configure-language-java)
- [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
- [Configuración del agente Java](https://opentelemetry.io/docs/zero-code/java/agent/configuration/)
- [New Relic OTLP](https://docs.newrelic.com/docs/opentelemetry/best-practices/opentelemetry-otlp/)
- [Referencia de NRQL](https://docs.newrelic.com/docs/nrql/nrql-syntax-clauses-functions/)
- [OIDC de GitHub Actions con Azure](https://learn.microsoft.com/azure/developer/github/connect-from-azure-openid-connect)
- [Diagnostic settings de Azure Monitor](https://learn.microsoft.com/azure/azure-monitor/essentials/diagnostic-settings)
- [Logs de diagnóstico de Azure SQL Database](https://learn.microsoft.com/azure/azure-sql/database/monitoring-metrics-diagnostic-telemetry-reference)
- [Azure Native New Relic Service](https://learn.microsoft.com/azure/partner-solutions/new-relic/overview)
- [Precios de Azure SQL Database](https://azure.microsoft.com/pricing/details/azure-sql-database/single/)
