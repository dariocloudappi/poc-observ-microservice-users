// =============================================================================
// Event Hub para reenviar los RESOURCE LOGS de Azure SQL a New Relic
// -----------------------------------------------------------------------------
// POR QUE HACE FALTA ESTO
// -----------------------
// La integracion nativa de New Relic (NewRelic.Observability/monitors) NO
// reenvia resource logs. Su objeto logRules solo tiene tres interruptores:
// sendAadLogs, sendActivityLogs y sendSubscriptionLogs. No existe ningun flag
// para logs de recurso, y SQLSecurityAuditEvents es exactamente eso.
//
// Referencia: NewRelic.Observability/monitors/tagRules, propiedad LogRules.
//
// El unico camino que Azure ofrece para sacar resource logs hacia un tercero es
// un Event Hub. De ahi los recoge el OpenTelemetry Collector del gateway con su
// receptor azureeventhub, los pasa por el mismo pipeline que el resto de la
// telemetria (redactado, severity, service.name) y los exporta a New Relic.
//
// Se eligio el colector y no la Function App de New Relic por dos motivos:
// la mitad de recursos (no hace falta Function ni su storage account) y los
// logs llegan enriquecidos en lugar de crudos.
//
// AVISO DE COSTE: un namespace de Event Hub factura por hora aunque no pase un
// solo mensaje. El tier Basic con 1 unidad de throughput es el minimo posible.
// =============================================================================

param location string
param namePrefix string
param tags object

@description('Nombre del Event Hub donde aterrizan los resource logs de SQL')
param hubName string = 'insights-logs-sql'

@description('Retencion en dias. Basic solo admite 1')
@minValue(1)
@maxValue(1)
param retentionDays int = 1

// El nombre del namespace es global en Azure, de ahi el uniqueString.
var namespaceName = 'evhns-${namePrefix}-${uniqueString(resourceGroup().id)}'

resource namespace 'Microsoft.EventHub/namespaces@2024-01-01' = {
  name: namespaceName
  location: location
  // sku antes de tags: regla S6975 de SonarQube sobre el orden de elementos.
  sku: {
    name: 'Basic'
    tier: 'Basic'
    capacity: 1
  }
  tags: tags
  properties: {
    // Basic no admite zonas ni auto-inflate. Se dejan explicitos para que
    // quede claro que no es un olvido.
    isAutoInflateEnabled: false
    zoneRedundant: false
    // Sensible a revision: el diagnostic setting de SQL se conecta por clave
    // compartida, asi que el acceso local no se puede desactivar. Es el mismo
    // mecanismo que usa la propia plataforma de Azure para escribir aqui.
    disableLocalAuth: false
    minimumTlsVersion: '1.2'
    publicNetworkAccess: 'Enabled'
  }
}

resource hub 'Microsoft.EventHub/namespaces/eventhubs@2024-01-01' = {
  parent: namespace
  name: hubName
  properties: {
    messageRetentionInDays: retentionDays
    // Una particion basta: el volumen de audit logs de una base de datos Basic
    // de PoC es minimo y mas particiones solo complican el consumo.
    partitionCount: 1
  }
}

// -----------------------------------------------------------------------------
// Dos reglas de autorizacion separadas, y no una compartida, porque los dos
// extremos necesitan permisos distintos:
//
//   sender   -> la usa el diagnostic setting de SQL. Solo Send.
//   listener -> la usa el colector OTel. Solo Listen.
//
// Con una sola regla que tuviese ambos permisos, la cadena de conexion que
// viaja al gateway podria tambien escribir eventos falsos.
// -----------------------------------------------------------------------------

resource senderRule 'Microsoft.EventHub/namespaces/eventhubs/authorizationRules@2024-01-01' = {
  parent: hub
  name: 'sql-diagnostics-sender'
  properties: {
    rights: [
      'Send'
    ]
  }
}

resource listenerRule 'Microsoft.EventHub/namespaces/eventhubs/authorizationRules@2024-01-01' = {
  parent: hub
  name: 'otel-collector-listener'
  properties: {
    rights: [
      'Listen'
    ]
  }
}

// El id de la regla de envio es lo que consume el diagnostic setting de SQL.
output senderRuleId string = senderRule.id
output namespaceName string = namespace.name
output hubName string = hub.name

// El id de la regla de escucha se publica, NO la cadena de conexion: un output
// de despliegue queda guardado en el historial de la suscripcion en claro. El
// pipeline del gateway recupera la clave con
//   az eventhubs eventhub authorization-rule keys list
// que devuelve el secreto sin dejarlo escrito en ningun sitio.
output listenerRuleId string = listenerRule.id
