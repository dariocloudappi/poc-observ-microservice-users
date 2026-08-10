// =============================================================================
// modules/sql.bicep - Azure SQL server and database
// -----------------------------------------------------------------------------
// Cheapest usable configuration for a short lived PoC:
//   Basic  5 DTU, 2 GB, locally redundant backup. Around 0,006 EUR per hour.
//   S0     10 DTU, use it if Basic throttles the workload.
//   GP_S_Gen5_1  serverless, auto pause after one hour of inactivity. More
//                expensive while active but it stops billing compute when idle.
//
// The schema is created by Hibernate on start up (ddl-auto: update), so there
// is no migration step.
// =============================================================================

param location string
param namePrefix string
param tags object
param sqlSkuName string
param databaseName string
param administratorLogin string

@secure()
param administratorPassword string

param logAnalyticsWorkspaceId string

@description('Send the diagnostic settings of the database to Log Analytics. Turn it off when the Azure Native New Relic Service already forwards them, to avoid paying twice for the same data')
param enableLogAnalytics bool = true

// Basic is capped at 2 GB by the service. The serverless option gets a larger
// cap because its storage is billed per GB actually used.
var maxSizeBytes = sqlSkuName == 'Basic' ? 2147483648 : 34359738368

var skuMap = {
  Basic: {
    name: 'Basic'
    tier: 'Basic'
    capacity: 5
  }
  S0: {
    name: 'S0'
    tier: 'Standard'
    capacity: 10
  }
  GP_S_Gen5_1: {
    name: 'GP_S_Gen5_1'
    tier: 'GeneralPurpose'
    family: 'Gen5'
    capacity: 1
  }
}

resource sqlServer 'Microsoft.Sql/servers@2023-08-01-preview' = {
  name: 'sql-${namePrefix}-${uniqueString(resourceGroup().id)}'
  location: location
  tags: tags
  properties: {
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorPassword
    version: '12.0'
    minimalTlsVersion: '1.2'
    publicNetworkAccess: 'Enabled'
  }
}

// The web app runs on the shared App Service infrastructure without VNet
// integration, so its outbound address is not predictable. The special
// 0.0.0.0 rule allows only traffic originated inside Azure, not the internet.
resource allowAzureServices 'Microsoft.Sql/servers/firewallRules@2023-08-01-preview' = {
  parent: sqlServer
  name: 'AllowAllWindowsAzureIps'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

var baseDatabaseProperties = {
  collation: 'SQL_Latin1_General_CP1_CI_AS'
  maxSizeBytes: maxSizeBytes
  zoneRedundant: false
  // Locally redundant backup is the cheapest option and enough for a PoC.
  requestedBackupStorageRedundancy: 'Local'
}

// Serverless only: pause the compute after one hour without connections.
var serverlessProperties = sqlSkuName == 'GP_S_Gen5_1' ? {
  autoPauseDelay: 60
  minCapacity: json('0.5')
} : {}

resource database 'Microsoft.Sql/servers/databases@2023-08-01-preview' = {
  parent: sqlServer
  name: databaseName
  location: location
  tags: tags
  sku: skuMap[sqlSkuName]
  properties: union(baseDatabaseProperties, serverlessProperties)
}

// Azure Monitor: query performance, errors, timeouts, deadlocks and blocks.
resource databaseDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = if (enableLogAnalytics) {
  name: 'diag-${databaseName}'
  scope: database
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        // Basic is the only metric category exposed by Azure SQL databases.
        category: 'Basic'
        enabled: true
      }
    ]
  }
}

output serverName string = sqlServer.name
output serverFqdn string = sqlServer.properties.fullyQualifiedDomainName
output databaseName string = database.name
output databaseId string = database.id
