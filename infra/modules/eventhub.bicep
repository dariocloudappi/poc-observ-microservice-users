// =============================================================================
// Event Hub para reenviar los RESOURCE LOGS de Azure SQL a New Relic
// -----------------------------------------------------------------------------
// MOTIVO
// ------
// La integracion nativa de New Relic (NewRelic.Observability/monitors) no
// reenvia resource logs. Su objeto logRules solo dispone de tres opciones:
// sendAadLogs, sendActivityLogs y sendSubscriptionLogs. No existe ninguna para
// logs de recurso, y SQLSecurityAuditEvents pertenece a esa categoria.
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
param tags object

@description('Nombre del namespace. Lo calcula main.bicep para poder derivar los ids sin leer los outputs de este modulo, que es condicional')
param namespaceName string

@description('Nombre del Event Hub donde aterrizan los resource logs de SQL')
param hubName string = 'insights-logs-sql'

@description('Nombre de la regla de autorizacion con permiso Send')
param senderRuleName string = 'sql-diagnostics-sender'

@description('Retencion en dias. Basic solo admite 1')
@minValue(1)
@maxValue(1)
param retentionDays int = 1

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

// La regla de ENVIO va a nivel de NAMESPACE, no de event hub, y no es una
// eleccion: Azure lo exige. Un diagnostic setting con una regla de hub falla con
//
//   Resource type 'microsoft.eventhub/namespaces/eventhubs/authorizationrules'
//   is invalid for property 'properties.eventHubAuthorizationRuleId'.
//   Expected types are 'microsoft.servicebus/namespaces/authorizationrules',
//   'microsoft.eventhub/namespaces/authorizationrules'
//
// Consecuencia a tener presente: una regla de namespace concede Send sobre
// TODOS los event hubs del namespace. Aqui es aceptable porque el namespace se
// crea solo para estos logs y contiene un unico hub, pero si algun dia se
// anaden mas hubs, este permiso los alcanza a todos.
resource senderRule 'Microsoft.EventHub/namespaces/authorizationRules@2024-01-01' = {
  parent: namespace
  name: senderRuleName
  properties: {
    rights: [
      'Send'
    ]
  }
}

// La regla de escucha permanece a nivel de hub: la consume el colector OTel
// mediante una cadena de conexion, no un diagnostic setting, por lo que no le
// aplica la restriccion anterior. Acotarla al hub responde al principio de
// minimo privilegio, ya que no puede leer otros hubs del namespace.
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
