# PoC microservice-users - Azure App Service + Azure SQL + New Relic

API REST de usuarios (Spring Boot 3.2.5, Java 17) desplegada en **Azure App Service Linux**
con **Azure SQL Database**, instrumentada con el **agente OpenTelemetry Java (zero-code)**
hacia **New Relic**, y con **Azure Monitor** recogiendo los logs y métricas de plataforma de
la aplicación y de la base de datos.

Todo el despliegue es automático desde GitHub Actions con autenticación **OIDC federada** (sin
client secrets) y está dimensionado para el mínimo coste posible en un PoC de vida corta.

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

## 2. Qué hay que configurar en GitHub

### 2.1 Secrets

`Settings > Secrets and variables > Actions > Secrets > New repository secret`

| Secret | Contenido | Cómo obtenerlo |
|--------|-----------|----------------|
| `AZURE_CLIENT_ID` | Application (client) ID de la app de Entra ID | salida del paso 3.1 |
| `AZURE_TENANT_ID` | Directory (tenant) ID | `az account show --query tenantId -o tsv` |
| `AZURE_SUBSCRIPTION_ID` | Id de la suscripción | `az account show --query id -o tsv` |
| `SQL_ADMIN_USER` | Login del administrador de SQL. **No puede ser** `admin`, `administrator`, `sa`, `root`, `dbmanager` ni `loginmanager` | por ejemplo `sqladminpoc` |
| `SQL_ADMIN_PASSWORD` | Contraseña del administrador de SQL. Mínimo 8 caracteres con 3 de estas 4 categorías: mayúscula, minúscula, dígito, símbolo | `openssl rand -base64 24` |
| `BASIC_AUTH_USER` | Usuario Basic Auth que acepta la API | debe coincidir con `UPSTREAM_USERS_BASIC_USER` en el repo del gateway |
| `BASIC_AUTH_PASSWORD` | Contraseña Basic Auth que acepta la API | `openssl rand -hex 24` |
| `NR_LICENSE_KEY` | License key de **ingesta** de New Relic (no una User API key) | New Relic > Administration > API keys > tipo `INGEST - LICENSE` |
| `GH_ADMIN_TOKEN` | PAT con escritura sobre *Environments* y *Secrets*. **Solo** lo usa el workflow `newrelic-azure-integration`; `deploy` no lo necesita | GitHub > Settings > Developer settings > Personal access tokens |
| `NR_ACCOUNT_ID` / `NR_ORGANIZATION_ID` / `NR_USER_EMAIL` | Identificadores de la cuenta de New Relic. **Solo** los usa el workflow `newrelic-native-integration` | one.newrelic.com > Administration |

El workflow verifica que los cinco secretos funcionales existen y falla en el primer paso si
falta alguno, antes de crear nada en Azure.

> **Por qué la validación está en el pipeline y no en la plantilla.** `infra/main.bicepparam`
> lee los valores con `readEnvironmentVariable`, y **Bicep resuelve esas llamadas en tiempo de
> compilación**. Si una llamada no declara valor por defecto, la ausencia de la variable no es
> un error de despliegue sino de compilación: el fichero aparece en rojo en VS Code y fallan
> `az bicep build-params` y `az deployment ... what-if` aunque solo quieras validar la
> plantilla. Por eso todas las llamadas declaran un valor por defecto (`''` para los secretos)
> y la comprobación de presencia vive en `deploy.yml`, que además da un mensaje claro
> indicando qué secreto falta.
>
> Consecuencia para el despliegue manual: **exporta las variables antes de lanzar
> `az deployment`**. Si olvidas alguna, el error llega de Azure (por ejemplo, contraseña de
> administrador de SQL vacía) en lugar de llegar de la validación local.

### 2.2 Variables

`Settings > Secrets and variables > Actions > Variables`. Todas son opcionales: si no se
definen se usa el valor por defecto.

| Variable | Descripción | Por defecto |
|----------|-------------|-------------|
| `AZURE_LOCATION` | Región de Azure | `westeurope` |
| `AZURE_RESOURCE_GROUP` | Resource group del PoC | `rg-usersvc` |
| `POC_NAME_PREFIX` | Prefijo de nombres, 3-12 caracteres | `usersvc` |
| `POC_OWNER` | Tag `owner` para control de coste | `unknown` |
| `POC_TTL` | Tag `ttl` | `1h` |
| `APP_SERVICE_SKU` | `F1`, `B1` o `B2` | `B1` |
| `SQL_SKU_NAME` | `Basic`, `S0` o `GP_S_Gen5_1` | `Basic` |
| `SQL_DATABASE_NAME` | Nombre de la base de datos | `sqldb-users` |
| `NR_OTLP_ENDPOINT` | Endpoint OTLP. EU: `https://otlp.eu01.nr-data.net:4318`, US: `https://otlp.nr-data.net:4318` | `https://otlp.eu01.nr-data.net:4318` |
| `OTEL_SERVICE_NAME` | Nombre del servicio en New Relic | `microservice-users` |
| `ENVIRONMENT` | Atributo `deployment.environment` | `poc` |
| `SERVICE_NAMESPACE` | Atributo `service.namespace`, común a todo el PoC | `poc-observability` |
| `LOG_LEVEL` | Nivel de log de la aplicación | `INFO` |
| `SQL_LOG_LEVEL` | Nivel del logger de sentencias SQL de Hibernate. `DEBUG` envia cada sentencia a New Relic como log | `INFO` |
| `LOG_RETENTION_DAYS` | Retención de Log Analytics | `30` |
| `LOG_DAILY_QUOTA_GB` | Tope diario de ingesta | `1` |
| `ENABLE_LOG_ANALYTICS` | Enviar los Diagnostic Settings a Log Analytics. Ponlo a `false` cuando el servicio nativo de New Relic ya reenvíe los logs, para no ingerir el mismo dato dos veces | `true` |
| `ENABLE_ACTIVITY_LOG_EXPORT` | Exportar el Activity Log de la suscripción | `false` |
| `ENABLE_SCHEDULED_CLEANUP` | Activa la limpieza horaria programada | desactivada |
| `POC_MAX_AGE_HOURS` | Edad máxima antes del borrado programado | `2` |

> **Importante:** `NR_OTLP_ENDPOINT` debe corresponder a la región de tu cuenta de New Relic.
> Una license key europea contra el endpoint de EE. UU. devuelve `403` y no se ingesta nada.

---

## 3. Qué hay que configurar en Azure

Todo lo siguiente se hace **una sola vez**. Después, cada despliegue es automático.

### 3.1 Aplicación de Entra ID con credenciales federadas (OIDC)

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

### 3.2 Credenciales federadas

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
hasta que recrees la credencial. Si eso te preocupa, GitHub permite cambiar la plantilla del
claim para usar el **id numérico del repositorio**, que es inmutable:

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

### 3.3 Permisos en Azure

El workflow crea el **resource group**, por lo que necesita permisos a nivel de suscripción:

```bash
az role assignment create \
  --assignee-object-id "$SP_ID" \
  --assignee-principal-type ServicePrincipal \
  --role "Contributor" \
  --scope "/subscriptions/$SUB_ID"
```

**Con `Contributor` a nivel de suscripción es suficiente.** Esta plantilla no crea
asignaciones de rol, así que **no** hace falta `Owner` ni `Role Based Access Control
Administrator` (a diferencia del repositorio del gateway, que sí asigna `AcrPull`).

Si tu organización no permite dar Contributor a nivel de suscripción:

```bash
# Alternativa: crear el resource group a mano y dar permisos solo sobre el
az group create --name rg-usersvc --location westeurope \
  --tags environment=poc ttl=1h project=poc-microservice-users

az role assignment create \
  --assignee-object-id "$SP_ID" --assignee-principal-type ServicePrincipal \
  --role "Contributor" --scope "/subscriptions/$SUB_ID/resourceGroups/rg-usersvc"
```

En ese caso el despliegue a nivel de suscripción seguirá fallando al crear el grupo. Hay que
cambiar `infra/main.bicep` a `targetScope = 'resourceGroup'`, eliminar el recurso
`Microsoft.Resources/resourceGroups` y usar `az deployment group create`. Está documentado
como limitación conocida.

### 3.4 Proveedores de recursos registrados

```bash
for ns in Microsoft.Web Microsoft.Sql Microsoft.OperationalInsights Microsoft.Insights; do
  az provider register --namespace "$ns" --wait
  az provider show --namespace "$ns" --query "{ns:namespace,state:registrationState}" -o tsv
done
```

### 3.5 Resumen de la configuración previa

| Paso | Dónde | Resultado |
|------|-------|-----------|
| Crear app de Entra ID + SP | Azure | `AZURE_CLIENT_ID` |
| Crear credencial federada `repo:ORG/REPO:ref:refs/heads/main` | Azure | GitHub puede autenticarse sin secreto |
| Asignar `Contributor` en la suscripción | Azure | El pipeline puede crear el RG y los recursos |
| Registrar proveedores `Web`, `Sql`, `OperationalInsights`, `Insights` | Azure | Los recursos se pueden crear |
| Crear los 8 secrets | GitHub | El pipeline tiene las credenciales |
| Crear las variables que quieras cambiar | GitHub | Ajuste de región, SKU y nombres |

---

## 4. Despliegue

### Automático

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
6. Sube el paquete con `az webapp deploy --type zip --clean true --restart true`.
7. Smoke tests sobre HTTPS:
   - `/actuator/health` debe devolver `"status":"UP"` (reintenta hasta 10 minutos por el
     arranque en frío de Spring Boot con agente).
   - La cadena de certificado se valida sin `--insecure`.
   - HTTP en claro debe redirigir, no servir contenido.
   - `/users` sin credenciales debe devolver `401`.
   - `/users` **con** credenciales debe devolver `200`, lo que prueba que la conexión a
     Azure SQL funciona de extremo a extremo.
8. Job `auto-destroy`: espera el TTL y borra el resource group.

> **Cambiar una variable o un secreto en GitHub se refleja en el App Service.** Los app
> settings no se declaran dentro de `siteConfig` del recurso `Microsoft.Web/sites`, sino como
> recurso hijo `Microsoft.Web/sites/config@appsettings`, que **reemplaza la colección entera**
> en cada despliegue. Declarados dentro de `siteConfig`, la plataforma los fusiona y un valor
> ya presente en la web app podia sobrevivir al nuevo despliegue. Ademas el pipeline:
>
> - lee los settings de vuelta y **falla** si alguno no coincide con lo desplegado (paso
>   *Verify the effective application settings*),
> - reinicia la web app despues del despliegue, para que la aplicacion relea los valores
>   aunque el paquete no haya cambiado.

### Manual desde tu máquina

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

### Ejecución local

```bash
cp .env.example .env      # y rellena los CHANGE_ME
set -a && . ./.env && set +a
mvn spring-boot:run
curl -u "$BASIC_AUTH_USER:$BASIC_AUTH_PASSWORD" http://localhost:8080/users
```

Para instrumentar también en local, descomenta `JAVA_TOOL_OPTIONS` en `.env` después de un
`mvn package`.

---

## 5. Coste estimado

Tarifas orientativas de West Europe. Consulta la calculadora oficial para los precios
vigentes de tu suscripción.

| Recurso | Configuración | Coste 1 hora | Coste 1 mes |
|---------|---------------|--------------|-------------|
| App Service Plan | Linux **B1** (1 vCPU, 1,75 GB) | ~0,018 EUR | ~12,90 EUR |
| Azure SQL Database | **Basic** 5 DTU, 2 GB | ~0,006 EUR | ~4,20 EUR |
| Log Analytics | 20-100 MB de ingesta | ~0,05-0,25 EUR | según uso |
| Salida de datos | Por debajo de la franquicia gratuita | ~0,00 EUR | ~0,00 EUR |
| **Total PoC de 1 hora** | | **por debajo de 0,30 EUR** | |

### Por qué B1 y no F1

`F1` (gratis) es una opción soportada por la plantilla (`APP_SERVICE_SKU=F1`) y cuesta 0 EUR,
pero para este servicio concreto tiene tres limitaciones relevantes:

- **60 minutos de CPU al día**: superada la cuota, la aplicación devuelve `403` el resto del
  día. Un Spring Boot con agente OTel consume varios minutos de CPU solo en arrancar.
- **1 GB de RAM compartida**: el JVM con `-Xmx512m` más el agente va justo, con riesgo de
  reinicios por memoria.
- **Sin Always On ni health check**: la app se descarga tras 20 minutos de inactividad y el
  siguiente arranque en frío tarda 1-2 minutos, lo que hace fallar los smoke tests.

B1 cuesta menos de **2 céntimos** en un PoC de una hora, así que el ahorro de F1 no compensa
el riesgo. Si aun así quieres F1, la plantilla lo soporta y desactiva automáticamente Always
On y el health check.

### Por qué SQL Basic

`Basic` (5 DTU, 2 GB) es la opción provisionada más barata y sobra para un CRUD de PoC.
Alternativas disponibles vía `SQL_SKU_NAME`:

- **`S0`** (10 DTU): si Basic limita las consultas. Aproximadamente 0,020 EUR/h.
- **`GP_S_Gen5_1`** (serverless): pausa el cómputo tras 60 minutos sin conexiones, pero
  mientras está activo cuesta bastante más que Basic. Interesante solo si el PoC va a estar
  encendido con largos periodos de inactividad.
- **Oferta gratuita de Azure SQL**: 100.000 vCore-segundos al mes, pero solo para **una base
  de datos por suscripción**. Como el PoC completo necesita dos (users y orders), no se usa
  aquí por defecto.

---

## 6. Observabilidad

### 6.1 Qué se envía a New Relic

El agente Java se adjunta con `JAVA_TOOL_OPTIONS=-javaagent:/home/site/wwwroot/otel-javaagent.jar`.
No hay una sola línea de instrumentación en el código.

| Señal | Contenido |
|-------|-----------|
| **Trazas** | Cada petición HTTP, con los spans hijos de JDBC (consultas a Azure SQL), incluyendo el tiempo de base de datos y la sentencia saneada |
| **Métricas** | JVM (heap, GC, hilos), HTTP server, pool de conexiones HikariCP y métricas de Micrometer expuestas por Actuator |
| **Logs** | Todo lo que pasa por Logback, correlacionado con `trace_id` y `span_id` vía MDC, incluidos los logs internos del propio agente |

Variables aplicadas (las que documenta New Relic para su endpoint OTLP):

| Variable | Valor | Por qué |
|----------|-------|---------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://otlp.eu01.nr-data.net:4318` | Endpoint OTLP de la cuenta |
| `OTEL_EXPORTER_OTLP_HEADERS` | `api-key=<license key>` | Autenticación de ingesta |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | Único protocolo OTLP admitido por New Relic |
| `OTEL_EXPORTER_OTLP_COMPRESSION` | `gzip` | Reduce el volumen de red |
| `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` | `delta` | New Relic ingesta temporalidad delta |
| `OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION` | `base2_exponential_bucket_histogram` | Percentiles precisos con menos datos |
| `OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT` | `4095` | New Relic descarta atributos más largos |
| `OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS` | `process.command_args` | Ese atributo supera el límite y no aporta valor |
| `OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED` | `true` | Reintentos ante errores transitorios |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `http` | Convenciones semánticas HTTP estables |
| `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES` | `*` | Envía `trace_id` y `span_id` como atributos del log |
| `OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES` | `true` | Convierte los `addKeyValue()` de SLF4J en atributos |
| `OTEL_JAVAAGENT_LOGGING` | `application` | Los logs del agente también llegan a New Relic |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name`, `service.version`, `service.namespace`, `deployment.environment`, `cloud.provider` | Permite filtrar y agrupar en New Relic |

### 6.2 Telemetría de la base de datos hacia OTLP

Los datos de Azure SQL llegan a New Relic por dos caminos distintos. El primero está
implementado y no cuesta nada; el segundo requiere infraestructura adicional.

**a) Desde la aplicación (implementado).** El agente instrumenta el driver JDBC, así que todo
lo que la aplicación hace contra la base de datos sale por OTLP:

| Señal | Qué se envía | Cómo se activa |
|-------|--------------|----------------|
| Trazas | Un span por sentencia SQL, con el texto saneado (sin valores literales), colgando del span de la petición HTTP | `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true` |
| Trazas | Un span por `DataSource.getConnection`, que hace visibles las esperas del pool | `OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED=true` (desactivado por defecto en el agente) |
| Métricas | Métricas del pool de conexiones (`hikaricp.connections.*`, `jdbc.connections.*`) y el resto de métricas de Micrometer que publica Actuator | `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` |
| Logs | Cada sentencia ejecutada, como registro de log correlacionado con su traza | Variable `SQL_LOG_LEVEL=DEBUG` (mapea a `logging.level.org.hibernate.SQL`) |

`SQL_LOG_LEVEL` está en `INFO` por defecto. Ponlo en `DEBUG` solo mientras lo necesites: sube
bastante el volumen de logs y con la cuota diaria de 1 GB del workspace es fácil llegar al
tope.

**b) Métricas y logs de plataforma del propio servicio SQL** (DTU consumidas, almacenamiento,
deadlocks, timeouts, Query Store). Esto lo genera Azure, no la aplicación, así que el agente
no puede verlo. Ahora mismo va a Log Analytics mediante el Diagnostic Setting
`diag-sqldb-users`. Para llevarlo también a New Relic hay dos opciones:

| Opción | Qué cubre | Coste en Azure | Estado |
|--------|-----------|----------------|--------|
| **Azure Native New Relic Service**, vinculando la cuenta existente | Métricas **y logs** de recurso, más el Activity Log. Azure crea y mantiene los Diagnostic Settings hacia New Relic por reglas de etiquetas | **0 EUR**: no hay Event Hub, ni Storage Account, ni Function App. La factura de New Relic sigue en New Relic | **Implementada y es la elegida**: workflow `newrelic-native-integration` |
| Integración por polling (app de Entra ID + `Reader` y `Monitoring Reader`) | **Solo métricas** de plataforma | 0 EUR, pero exige un client secret que caduca | Implementada como **alternativa**: workflow `newrelic-azure-integration` |
| Event Hub o Blob Storage + Function de reenvío | Métricas **y logs** de plataforma | Event Hubs Basic ~9 EUR/mes, o una Storage Account, **más** una Function App | Descartada |

**Decisión: el servicio nativo.** Es a la vez lo recomendado para Azure, lo más barato (cero
recursos de reenvío) y lo único que cubre logs, métricas y Activity Log de una sola pieza.
La cuenta de New Relic se **vincula**, no se crea: `accountCreationSource` y
`orgCreationSource` valen `NEWRELIC`, así que no aparece un recurso SaaS de Marketplace y la
facturación de New Relic no cambia de sitio.

Puntos que sostienen la decisión:

- **Event Hub no es obligatorio.** New Relic documenta *dos* variantes del reenvío clásico,
  ambas con Function App: Event Hub (la que recomiendan) o Blob Storage. Y el servicio nativo
  no necesita ninguna de las dos.
- Según Microsoft, con Azure Native Integrations "los Diagnostic Settings se añaden
  automáticamente a los recursos que empiezan a coincidir y se eliminan de los que dejan de
  coincidir", y para las métricas Azure crea una identidad administrada y le asigna
  `Monitoring Reader` por su cuenta. Sin app de Entra ID y sin client secret.
- Solo se envían las categorías de log soportadas. Las de Azure SQL Database (`Errors`,
  `Timeouts`, `Blocks`, `Deadlocks`, `QueryStoreRuntimeStatistics`...) son categorías estándar
  de Azure Monitor, así que entran por el mecanismo genérico.

#### Cómo se evita la información duplicada

Con todo apuntando a New Relic, el riesgo real es pagar dos veces por el mismo dato. Estas son
las tres duplicidades posibles y cómo están resueltas:

| Duplicidad | Por qué ocurriría | Cómo se evita |
|------------|-------------------|---------------|
| Logs de la aplicación | El agente OTel ya los manda a New Relic, y el reenvío de plataforma mandaría las mismas líneas otra vez como `AppServiceConsoleLogs` | La web app se etiqueta `newrelicLogs=exclude` y la regla de logs lleva esa etiqueta con acción **Exclude**, que tiene prioridad sobre cualquier inclusión. Sus **métricas** de plataforma sí se recogen: esas el agente no las ve |
| Mismos logs en Log Analytics y en New Relic | Los Diagnostic Settings del PoC apuntan al workspace, y el servicio nativo añade otro hacia New Relic | Variable `ENABLE_LOG_ANALYTICS=false`: deja de crear los Diagnostic Settings hacia el workspace. New Relic pasa a ser el único destino |
| Métricas duplicadas | El polling y el servicio nativo recogen las mismas métricas de plataforma | Son **excluyentes**: usa uno u otro, nunca los dos. El workflow de polling lleva el aviso en su cabecera |
| Dos monitores nativos | Un recurso monitor por repositorio, ambos vinculados a la misma organización | El monitor se despliega **solo desde `poc-microservice-users`** y el workflow aborta si detecta más de uno en la suscripción |

El workspace de Log Analytics se sigue creando aunque `ENABLE_LOG_ANALYTICS` sea `false`: un
workspace sin ingesta no cuesta nada, y así el parámetro se puede cambiar sin recrear nada.

### Servicio nativo de New Relic, automatizado (vía elegida)

El workflow **`newrelic-native-integration`** (manual, se ejecuta **una sola vez por
suscripción**) despliega `infra/newrelic.bicep`, que crea:

- un resource group aparte, `rg-newrelic-shared`, que **sobrevive al destroy del PoC**;
- el recurso `NewRelic.Observability/monitors` vinculado a la cuenta de New Relic existente;
- sus `tagRules`: métricas de toda la suscripción, logs de recurso y Activity Log, con la
  etiqueta `newrelicLogs=exclude` como regla de exclusión.

A partir de ahí, cada despliegue del PoC solo tiene que crear recursos: Azure les pone y les
quita los Diagnostic Settings hacia New Relic por sí mismo.

#### Secrets que necesita

| Secret | Dónde se saca |
|--------|---------------|
| `NR_ACCOUNT_ID` | one.newrelic.com > Administration > Access management > Accounts |
| `NR_ORGANIZATION_ID` | one.newrelic.com > Administration > Organization |
| `NR_USER_EMAIL` | Email del propietario de la cuenta; el resource provider lo exige |
| `NR_LICENSE_KEY` | El mismo ingest key que ya usa la aplicación |

Y la variable `NR_REGION` (`eu` o `us`), que debe coincidir con la región de la cuenta.

#### Después de ejecutarlo

1. Pon la variable `ENABLE_LOG_ANALYTICS=false` en los dos repositorios de microservicios y
   vuelve a desplegar. Así los logs de plataforma dejan de ir también al workspace y no se
   pagan dos veces.
2. Comprueba en el portal, sobre el servidor SQL: **Diagnostic settings** debe mostrar una
   entrada hacia New Relic creada por Azure.
3. En la web app **no** debe aparecer esa entrada: está excluida a propósito.

#### Limitación con un PoC de una hora

"Cambio de reglas" no es solo editar las reglas. Son tres casos, y el segundo ocurre en
**cada despliegue**:

1. **Editar las reglas**: volver a ejecutar este workflow cambiando los flags o los
   `filteringTags`.
2. **Un recurso nuevo que empieza a coincidir**: cada `deploy` crea un servidor SQL y una base
   de datos nuevos, con nombres nuevos, que pasan a encajar en las reglas ya existentes.
3. **Un recurso al que le cambian las etiquetas**: por ejemplo la web app al recibir
   `newrelicLogs=exclude`.

La documentación de Microsoft se contradice sobre cuánto tarda. El FAQ del servicio dice
*"hasta una hora"* y pone como ejemplo justo el caso 2: *"if you add a new resource to tag
rules, it would take about one hour for log forwarding to start for that resource"*. La página
general de Azure Native Integrations dice *"changes to tag rules take effect within a few
minutes"*. No se puede resolver leyendo: hay que medirlo en tu suscripción.

Lo que se retrasa es **la creación del Diagnostic Setting** sobre el recurso nuevo. Una vez
creado, el flujo de logs ya no arrastra ese retraso, así que la comprobación es binaria:

```bash
SQL_ID=$(az sql db show -g rg-usersvc -s <servidor> -n sqldb-users --query id -o tsv)
az monitor diagnostic-settings list --resource "$SQL_ID" -o table
```

En cuanto aparezca la entrada hacia New Relic, los logs de plataforma están fluyendo.

Dos matices: el retraso está documentado **para el flujo de logs**, no para las métricas, que
las recoge el resource provider por su cuenta; y no afecta a la telemetría OTLP de la
aplicación, que llega desde el primer segundo.

**Implicación para un PoC de una hora:** el patrón "creo el resource group y lo destruyo en 60
minutos" es el peor caso posible para los logs de plataforma. Si la demo va de eso, despliega
con `auto_destroy_minutes = 0`, espera a que aparezca el Diagnostic Setting y a partir de ahí
redespliega solo la aplicación: los recursos persisten y el reloj de propagación ya no cuenta.

### Integración por polling, alternativa

El workflow **`newrelic-azure-integration`** (`workflow_dispatch`, manual) deja la
integración lista sin pasos manuales en el portal. Es **idempotente**: si la aplicación ya
existe la reutiliza, y los roles ya asignados no se vuelven a asignar. Ejecutarlo dos veces
no cambia nada.

Qué hace, siguiendo literalmente lo que exige la documentación de New Relic:

1. Registra el proveedor `microsoft.insights` en la suscripción si no lo estaba.
2. Busca una aplicación de Entra ID con el nombre indicado (por defecto
   `NewRelic-Integrations`, el que recomienda New Relic). **Si existe, la reutiliza**; si no,
   la crea. Es una aplicación intermedia, genérica y compartida por todo el PoC: no hace
   falta una por servicio.
3. Se asegura de que tiene service principal.
4. Le asigna **`Reader`** y **`Monitoring Reader`** a nivel de **suscripción**. New Relic pide
   los dos, no solo `Monitoring Reader`.
5. Crea un client secret solo si la aplicación no tenía ninguno, o si se lo pides con
   `rotate_secret=true`.
6. Guarda `NR_AZURE_CLIENT_ID`, `NR_AZURE_TENANT_ID`, `NR_AZURE_SUBSCRIPTION_ID` y
   `NR_AZURE_CLIENT_SECRET` como secretos del **GitHub Environment** indicado, creándolo si no
   existe.
7. Escribe en el resumen de la ejecución los cuatro valores no sensibles y el último paso que
   sí es manual: pegarlos en `one.newrelic.com > Infrastructure > Azure > Add an Azure account`.

#### Inputs

| Input | Descripción | Por defecto |
|-------|-------------|-------------|
| `app_display_name` | Nombre de la aplicación de Entra ID compartida | `NewRelic-Integrations` |
| `environment_name` | GitHub Environment donde se guardan los valores | `newrelic` |
| `rotate_secret` | Crear un client secret nuevo aunque ya haya uno | `false` |
| `secret_years` | Vigencia del secreto, 1 o 2 años (máximo que admite Azure) | `1` |
| `store_in_github` | Guardar los valores como secretos de GitHub | `true` |

#### Permisos que necesita, y son más que los de `deploy`

Este workflow toca el directorio y el RBAC de la suscripción, así que la identidad federada
necesita **dos permisos que `deploy.yml` no necesita**:

| Permiso | Para qué | Cómo darlo |
|---------|----------|------------|
| Rol de directorio **Application Developer** (o permiso Graph `Application.ReadWrite.All`) | Crear la aplicación de Entra ID | `az rest --method POST --url "https://graph.microsoft.com/v1.0/directoryRoles/roleTemplateId=cf1c38e5-3621-4004-a7cb-879624dced7c/members/$ref" --body "{\"@odata.id\":\"https://graph.microsoft.com/v1.0/directoryObjects/<OBJECT_ID_DEL_SP>\"}"` |
| **Owner** o **Role Based Access Control Administrator** en la suscripción | Asignar `Reader` y `Monitoring Reader` | `az role assignment create --assignee-object-id <OBJECT_ID_DEL_SP> --assignee-principal-type ServicePrincipal --role "Role Based Access Control Administrator" --scope "/subscriptions/$SUB_ID"` |

Si no puedes o no quieres conceder esos permisos, ejecuta el workflow con
`store_in_github=false` y verás en el resumen exactamente qué falta por hacer a mano; o crea
la aplicación y los roles una vez desde el portal y no vuelvas a ejecutarlo.

#### Secreto necesario: `GH_ADMIN_TOKEN`

`GITHUB_TOKEN` **no puede escribir secretos ni crear environments**. Para que el paso de
guardado funcione hace falta un PAT en el secreto `GH_ADMIN_TOKEN` con permisos de escritura
sobre *Environments* y *Secrets* del repositorio (fine-grained) o alcance `repo` (clásico).
El workflow lo comprueba **antes** de tocar Azure, para no crear un client secret y perderlo.

Si el repositorio es privado y la cuenta está en plan Free, la API de environments falla: el
workflow lo detecta y guarda los valores como secretos a nivel de repositorio.

#### Caducidad

El client secret caduca (1 o 2 años). Antes de esa fecha, vuelve a ejecutar el workflow con
`rotate_secret=true` y actualiza el valor en la UI de New Relic. La rotación usa `--append`,
así que el secreto anterior sigue siendo válido hasta que caduque y la integración no se corta.

#### Sobre los GitHub Environments y OIDC

Si además decides que los despliegues pasen por un environment (añadiendo `environment: poc`
al job de `deploy.yml`), **cambia el claim `sub` del token OIDC**: pasa de
`repo:OWNER/REPO:ref:refs/heads/main` a `repo:OWNER/REPO:environment:poc`, y hay que crear una
credencial federada adicional con ese subject. El paso *Show the OIDC subject expected by
Azure* te dirá el valor exacto en cuanto lo pruebes.

### 6.3 Verificar que llegan los datos

Genera tráfico y espera 1-2 minutos:

```bash
URL="https://$(az webapp show -g rg-usersvc -n <webapp> --query defaultHostName -o tsv)"
for i in $(seq 1 30); do
  curl -s -o /dev/null -u "$BASIC_AUTH_USER:$BASIC_AUTH_PASSWORD" "$URL/users"
done
```

Consultas NRQL:

```sql
-- Trazas del servicio
SELECT count(*) FROM Span
WHERE service.name = 'microservice-users' SINCE 30 minutes ago TIMESERIES

-- Spans de base de datos: confirma que la instrumentacion JDBC funciona
SELECT count(*), average(duration.ms) FROM Span
WHERE service.name = 'microservice-users' AND db.system IS NOT NULL
SINCE 30 minutes ago FACET name

-- Logs correlacionados con trazas
SELECT timestamp, message, trace.id, span.id FROM Log
WHERE service.name = 'microservice-users' SINCE 30 minutes ago LIMIT 50

-- Metricas de JVM
SELECT latest(jvm.memory.used) FROM Metric
WHERE service.name = 'microservice-users' SINCE 30 minutes ago FACET jvm.memory.pool.name

-- Todo el PoC junto (gateway + microservicios)
SELECT count(*) FROM Span
WHERE service.namespace = 'poc-observability' SINCE 1 hour ago FACET service.name
```

Si no llega nada, revisa el log del contenedor: el agente escribe sus errores de exportación
al arrancar.

```bash
az webapp log tail --resource-group rg-usersvc --name <webapp>
```

Causas habituales: license key de otra región, `NR_OTLP_ENDPOINT` sin `https://`, o una User
API key en lugar de una license key de ingesta.

### 6.4 Azure Monitor

Se activan dos Diagnostic Settings hacia el mismo workspace:

| Origen | Categorías | Tabla en Log Analytics |
|--------|-----------|------------------------|
| Web App | `allLogs` (HTTP, consola, aplicación, plataforma, auditoría, IPSec) + `AllMetrics` | `AppServiceHTTPLogs`, `AppServiceConsoleLogs`, `AppServiceAppLogs`, `AppServicePlatformLogs`, `AppServiceAuditLogs` |
| SQL Database | `allLogs` (errores, timeouts, bloqueos, deadlocks, Query Store) + métricas `Basic` | `AzureDiagnostics`, `AzureMetrics` |

Además se habilita el logging a sistema de ficheros de la Web App (`applicationLogs` nivel
`Information` y `httpLogs`), requisito para que esas categorías lleguen al workspace.

Consultas de verificación:

```bash
WS=$(az monitor log-analytics workspace show -g rg-usersvc -n log-usersvc --query customerId -o tsv)

# Peticiones HTTP atendidas
az monitor log-analytics query --workspace "$WS" --analytics-query "
AppServiceHTTPLogs
| project TimeGenerated, CsMethod, CsUriStem, ScStatus, TimeTaken
| order by TimeGenerated desc | take 50"

# Salida de consola de la aplicacion
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

### 6.5 Correlacionar Azure Monitor con New Relic

Los logs de plataforma están en Log Analytics y los de aplicación en New Relic. Para una vista
unificada:

1. **Azure Monitor -> Event Hub -> New Relic**: añadir `eventHubAuthorizationRuleId` al
   Diagnostic Setting y desplegar la función de reenvío de New Relic. Es la vía completa,
   pero un Event Hub Namespace Basic añade ~10 EUR/mes, así que queda fuera del PoC.
2. **Integración Azure de New Relic** (`one.newrelic.com > Infrastructure > Azure`): trae
   métricas de plataforma por polling, sin logs.
3. **Correlación manual**: los `AppServiceHTTPLogs` incluyen `CsUriStem` y marca de tiempo, y
   los logs de New Relic llevan `trace.id`. Para un PoC es suficiente para saltar de un
   sistema al otro.

---

## 7. Variables de entorno de la aplicación

Ver [`.env.example`](.env.example) para el fichero completo. Resumen de lo funcional:

| Variable | Descripción | Origen en Azure |
|----------|-------------|-----------------|
| `PORT` | Puerto de escucha | app setting, fijo a `8080` |
| `LOG_LEVEL` | Nivel de log raíz | variable `LOG_LEVEL` |
| `ENVIRONMENT` | `deployment.environment` | variable `ENVIRONMENT` |
| `SQL_SERVER` | FQDN del servidor SQL | salida del Bicep |
| `SQL_SERVER_NAME` | Nombre corto del servidor, necesario para el login `user@server` | salida del Bicep |
| `SQL_DATABASE` | Nombre de la base de datos | variable `SQL_DATABASE_NAME` |
| `SQL_USERNAME` / `SQL_PASSWORD` | Credenciales de SQL | secrets `SQL_ADMIN_USER` / `SQL_ADMIN_PASSWORD` |
| `BASIC_AUTH_USER` / `BASIC_AUTH_PASSWORD` | Credenciales que exige la API | secrets homónimos |
| `OTEL_*` | Configuración del agente | ver sección 6.1 |

> `SQL_SERVER_NAME` no existía en la versión anterior del `.env.example` aunque
> `application.yaml` la usa para construir la URL JDBC. Se ha añadido: sin ella, el usuario
> de conexión quedaba como `usuario@` y el login fallaba.

---

## 8. Endpoints

| Método | Ruta | Autenticación | Descripción |
|--------|------|---------------|-------------|
| `GET` | `/actuator/health` | pública | Salud de la aplicación y de la base de datos |
| `GET` | `/actuator/info` | pública | Información de la build |
| `GET` | `/status` | Basic Auth | Estado de las dependencias |
| `GET` | `/users` | Basic Auth | Lista de usuarios |
| `GET` | `/users/{id}` | Basic Auth | Usuario por id |
| `POST` | `/users` | Basic Auth | Alta de usuario |
| `PATCH` | `/users/{id}` | Basic Auth | Modificación |
| `DELETE` | `/users/{id}` | Basic Auth | Baja |

El esquema de base de datos lo crea Hibernate al arrancar (`ddl-auto: update`), no hay paso de
migración.

---

## 9. Limpieza de recursos

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

---

## 10. Seguridad

| Aspecto | Estado |
|---------|--------|
| Credenciales en el repositorio | Ninguna. `.env.example` solo tiene placeholders `CHANGE_ME_*` y `.gitignore` cubre `.env*`, `*.pem`, `*.key`, `*.pfx` |
| Autenticación del pipeline | OIDC federado, sin client secret almacenado |
| Secretos en logs | Enmascarados con `::add-mask::` antes de usarse; el pipeline falla si falta alguno |
| Secretos hacia Bicep | Como variables de entorno leídas por `.bicepparam`, nunca como argumentos de línea de comandos; los parámetros son `@secure()` y no aparecen en el historial de despliegues |
| Tráfico entrante | `httpsOnly: true`, TLS mínimo 1.2, FTPS deshabilitado |
| Tráfico a la base de datos | TLS mínimo 1.2 en el servidor y `encrypt=true` con validación de certificado en la URL JDBC |
| Exposición de la base de datos | Firewall solo con la regla de servicios de Azure (`0.0.0.0`), sin acceso desde internet |
| Autorización de la API | Basic Auth sobre todos los endpoints salvo `/actuator/health` e `/actuator/info` |
| Identidad de la aplicación | Identidad administrada de sistema activada, lista para Key Vault o acceso passwordless a SQL |

### Riesgos residuales

1. **La contraseña de SQL se guarda como app setting** de la Web App: legible por cualquiera
   con permisos de lectura del recurso. Mejora natural: guardarla en Key Vault y referenciarla
   con `@Microsoft.KeyVault(SecretUri=...)` usando la identidad administrada ya habilitada, o
   pasar a autenticación sin contraseña con `authentication=ActiveDirectoryMSI` en la URL JDBC
   y un usuario contenido creado desde Entra ID.
2. **`/actuator/health` es público y con `show-details: always`**, por lo que expone el estado
   de la base de datos a cualquiera. Para producción, restringir a `when-authorized`.
3. **La regla de firewall de servicios de Azure permite conexiones desde cualquier
   suscripción de Azure**, no solo la tuya. La alternativa correcta es integración con VNet y
   private endpoint, que en App Service requiere plan Standard o superior.
4. **`ddl-auto: update`** deja que Hibernate modifique el esquema en caliente. Aceptable en
   un PoC, no en producción.

### Rotación de secretos

| Secreto | Cómo rotarlo |
|---------|--------------|
| `SQL_ADMIN_PASSWORD` | `az sql server update -g rg-usersvc -n <server> --admin-password <nueva>`, actualizar el GitHub Secret y relanzar `deploy` |
| `BASIC_AUTH_*` | Actualizar el secret, relanzar `deploy` y actualizar en paralelo `UPSTREAM_USERS_BASIC_*` en el repositorio del gateway |
| `NR_LICENSE_KEY` | Crear una key nueva en New Relic, actualizar el secret, relanzar `deploy` y borrar la antigua |
| Credencial federada OIDC | `az ad app federated-credential delete` y volver a crearla. No hay secreto que rotar |

---

## 11. Referencias

- [Configurar una app Java en App Service](https://learn.microsoft.com/azure/app-service/configure-language-java)
- [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
- [New Relic OTLP](https://docs.newrelic.com/docs/opentelemetry/best-practices/opentelemetry-otlp/)
- [OIDC de GitHub Actions con Azure](https://learn.microsoft.com/azure/developer/github/connect-from-azure-openid-connect)
- [Diagnostic settings de Azure Monitor](https://learn.microsoft.com/azure/azure-monitor/essentials/diagnostic-settings)
- [Precios de Azure SQL Database](https://azure.microsoft.com/pricing/details/azure-sql-database/single/)
