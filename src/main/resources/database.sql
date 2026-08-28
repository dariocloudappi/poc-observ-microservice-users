-- ============================================================
-- DDL - poc-microservice-users
-- Base de datos: Azure SQL Database / SQL Server 2019+
-- Identificadores: UNIQUEIDENTIFIER (UUID)
-- ============================================================
-- La aplicacion arranca con ddl-auto: none, asi que este script
-- es la unica fuente del esquema. El pipeline lo aplica en cada
-- despliegue, por eso cada sentencia es idempotente: se puede
-- ejecutar tantas veces como haga falta sin fallar.
--
-- Los separadores GO son directivas de SQLCMD, no T-SQL: este
-- fichero se ejecuta con sqlcmd o con azure/sql-action.
-- ============================================================

-- Crear base de datos (la crea el Bicep, se deja como referencia)
-- CREATE DATABASE usersdb;
-- GO
-- USE usersdb;
-- GO

-- ============================================================
-- Tabla: users
-- ============================================================
-- Refleja la entidad com.example.microserviceusersapplication.models.User.
--
-- Los tipos y los nombres de columna se han obtenido de los metadatos de mapeo
-- de Hibernate 6.4 con SQLServerDialect y la estrategia de nomenclatura
-- CamelCaseToUnderscoresNamingStrategy, que es la que aplica Spring Boot 3:
--
--   id          uniqueidentifier   NOT NULL   (clave primaria)
--   created_at  datetimeoffset(6)  NULL
--   email       varchar(255)       NULL
--   name        varchar(255)       NULL
--   updated_at  datetimeoffset(6)  NULL
--
-- created_at y updated_at son DATETIMEOFFSET y no DATETIME2 porque la entidad
-- los declara como java.time.Instant, que Hibernate 6 mapea a "timestamp with
-- time zone". En microservice-orders esos mismos campos son LocalDateTime y por
-- eso alli el tipo correcto si es DATETIME2.
--
-- Las cuatro columnas admiten NULL porque la entidad no declara
-- @Column(nullable = false) en ninguna: el esquema reproduce lo que la
-- aplicacion espera. La obligatoriedad se valida en la capa de DTO.
IF OBJECT_ID('dbo.users', 'U') IS NULL
BEGIN
    CREATE TABLE users (
        id         UNIQUEIDENTIFIER  NOT NULL DEFAULT NEWID(),
        name       VARCHAR(255)      NULL,
        email      VARCHAR(255)      NULL,
        created_at DATETIMEOFFSET(6) NULL,
        updated_at DATETIMEOFFSET(6) NULL,

        CONSTRAINT PK_users PRIMARY KEY (id)
    );
END
GO

-- Indice para UserRepository.existsByEmail, que se ejecuta en cada alta de
-- usuario para rechazar duplicados.
--
-- No es UNIQUE: la unicidad la comprueba hoy la aplicacion y una restriccion en
-- la base de datos fallaria al aplicarse sobre datos que ya contengan
-- duplicados. Convertirlo en UNIQUE es una decision aparte, que exige comprobar
-- primero los datos existentes.
IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'IX_users_email' AND object_id = OBJECT_ID('dbo.users')
)
    CREATE NONCLUSTERED INDEX IX_users_email
        ON users (email);
GO

-- ============================================================
-- Verificacion
-- ============================================================
SELECT 'users' AS tabla, COUNT(*) AS registros FROM users;
GO
