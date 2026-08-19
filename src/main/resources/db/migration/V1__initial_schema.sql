-- Esquema completo y actual de Relay.
--
-- Es una sola migración a propósito: consolida lo que hasta ahora generaba
-- `ddl-auto: update` más dos rondas de DDL manual (ver docs/features/webhooks.md
-- sección 6). No se reconstruye la historia en V2/V3 porque nadie fuera de la
-- máquina de desarrollo tuvo jamás las versiones intermedias.
--
-- Generado desde el esquema real de la DB de dev, no desde las @Entity: la DB es
-- la que tiene los parches manuales aplicados y la que hoy funciona.

CREATE TABLE payments (
    id                      uniqueidentifier  NOT NULL,
    merchant_id             uniqueidentifier  NOT NULL,
    idempotency_key         varchar(255)      NOT NULL,
    amount                  bigint            NOT NULL,   -- centavos, nunca decimal
    currency                varchar(3)        NOT NULL,   -- ISO 4217
    status                  varchar(255)      NOT NULL,
    reference               varchar(255)      NULL,       -- referencia del merchant, opcional
    provider_transaction_id varchar(255)      NULL,
    [version]               bigint            NOT NULL
        CONSTRAINT df_payments_version DEFAULT 0,
    created_at              datetimeoffset(7) NOT NULL,
    updated_at              datetimeoffset(7) NOT NULL,

    CONSTRAINT pk_payments PRIMARY KEY (id),

    CONSTRAINT uk_merchant_idempotency_key UNIQUE (merchant_id, idempotency_key),

    -- Este CHECK es el que motivó la migración a Flyway: Hibernate lo genera solo
    -- a partir del enum, pero `ddl-auto: update` no lo actualiza cuando se agrega
    -- un valor nuevo, así que el estado nuevo fallaba al persistir sin aviso.
    -- Versionado acá, agregar un estado es editar una migración y no descubrirlo
    -- en runtime.
    CONSTRAINT ck_payments_status CHECK (status IN (
        'PENDING', 'PROCESSING', 'AWAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED', 'UNKNOWN'
    ))
);

-- NO único a propósito: en SQL Server un UNIQUE trata los NULL como iguales y
-- admite uno solo, y la mayoría de los pagos tienen provider_transaction_id NULL
-- (TIMEOUT nunca lo recibe y SERVER_ERROR lo devuelve null por diseño).
CREATE INDEX ix_payments_provider_transaction_id
    ON payments (provider_transaction_id);

CREATE TABLE webhook_events (
    id                uniqueidentifier  NOT NULL,
    provider_event_id varchar(255)      NOT NULL,
    raw_payload       nvarchar(max)     NOT NULL,  -- cuerpo crudo: es lo que se firma
    status            varchar(255)      NOT NULL,
    received_at       datetimeoffset(7) NOT NULL,
    processed_at      datetimeoffset(7) NULL,

    CONSTRAINT pk_webhook_events PRIMARY KEY (id),

    -- Barrera de idempotencia de la ingesta: la reentrega del mismo evento del
    -- proveedor rebota acá, no en el chequeo previo (que es solo un atajo barato).
    CONSTRAINT uk_webhook_provider_event_id UNIQUE (provider_event_id),

    CONSTRAINT ck_webhook_events_status CHECK (status IN (
        'RECEIVED', 'PROCESSED', 'FAILED'
    ))
);
