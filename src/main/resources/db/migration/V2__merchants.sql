-- Merchants: la identidad que autentica contra Relay.
--
-- Hasta ahora `merchant_id` era un UUID que el cliente afirmaba en un header
-- (`X-Merchant-Id`) y que no existía en ninguna tabla. Esta migración le da
-- respaldo: un merchant es una fila con credenciales de cliente (client_credentials),
-- y el merchant_id de un pago pasa a salir de un token firmado por Relay.

CREATE TABLE merchants (
    id                 uniqueidentifier NOT NULL,
    name               varchar(255)     NOT NULL,

    -- Identificador público del cliente. Viaja en el request del token y en logs;
    -- no es secreto. UNIQUE porque es la clave de búsqueda de la autenticación.
    client_id          varchar(64)      NOT NULL,

    -- Hash BCrypt del clientSecret, nunca el secret. 60 caracteres hoy ($2a$12$ +
    -- 53); varchar(100) deja margen para un prefijo distinto sin migrar la columna.
    client_secret_hash varchar(100)     NOT NULL,

    status             varchar(32)      NOT NULL,
    created_at         datetimeoffset(7) NOT NULL,
    updated_at         datetimeoffset(7) NOT NULL,

    CONSTRAINT pk_merchants PRIMARY KEY (id),

    CONSTRAINT uk_merchants_client_id UNIQUE (client_id),

    -- Mismo criterio que ck_payments_status: el CHECK se versiona acá y no lo
    -- genera Hibernate, así que agregar un estado es editar una migración y no
    -- descubrir en runtime que la fila no persiste.
    CONSTRAINT ck_merchants_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- Deliberadamente NO se agrega FK payments.merchant_id -> merchants.id.
-- La tabla payments ya tiene filas con merchant_id que no corresponden a ningún
-- merchant registrado (venían del header libre), y una FK haría fallar la
-- migración sobre datos existentes. El aislamiento no depende de la FK: depende
-- de que el merchant_id salga del token y de que toda consulta filtre por él.
