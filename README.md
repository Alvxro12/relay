# Relay

Payment orchestration gateway para e-commerce.

Java 21 · Spring Boot · SQL Server · JPA/Hibernate · RabbitMQ · Docker

## El problema

Un comercio que quiere aceptar pagos mediante distintos proveedores externos normalmente tiene que integrarse individualmente con cada uno. Cada proveedor tiene su propia API, formatos de respuesta y mecanismos de confirmación.

Relay centraliza esa integración: el comercio se integra una sola vez con Relay, y Relay coordina con el proveedor correspondiente.

Relay no procesa pagos ni mueve dinero. Esa responsabilidad permanece en el proveedor externo. Relay se encarga de coordinar el flujo y mantener un estado consistente ante concurrencia, timeouts, respuestas ambiguas, reintentos y fallos de infraestructura.

## Problemas que busca solucionar

### Idempotencia bajo concurrencia

Dos requests simultáneos con la misma `Idempotency-Key` no deben producir dos pagos, incluso si llegan al mismo instante.

Esto se prueba mediante tests de concurrencia reales utilizando `ExecutorService` y `CountDownLatch` (`PaymentServiceConcurrencyTest`).

### Estado UNKNOWN

Si Relay no puede confirmar el resultado de un cobro —por ejemplo, un timeout o un error del proveedor sin respuesta completa— no asume éxito ni fallo.

El estado `UNKNOWN` representa explícitamente esta incertidumbre y permite resolverla posteriormente mediante reconciliación.

### Procesamiento asíncrono

El cobro no se ejecuta directamente durante la creación del pago.

Después de confirmar la transacción de persistencia, Relay publica un evento en RabbitMQ. Un consumer independiente procesa el cobro y actualiza el estado del pago.

Esto desacopla la creación del pago de la ejecución del cobro y permite manejar fallos mediante reintentos y dead-letter queues.

### Consistencia transaccional

Los eventos de pago se publican después del commit de la transacción: el insert registra una `TransactionSynchronization` con `TransactionSynchronizationManager.registerSynchronization(...)` y publica al broker dentro de `afterCommit()`. Ver `PaymentInsertService` y `WebhookEventInsertService`.

Esto evita que el consumer procese un evento antes de que el pago sea visible en la base de datos. Durante el desarrollo se identificó y corrigió esta condición de carrera (dual-write race) mediante testing de integración.

## Arquitectura

Relay utiliza un monolito modular. El problema actual no requiere distribuir el sistema en múltiples servicios — introducir microservicios aumentaría la complejidad operacional sin aportar valor al MVP.

La organización es por feature (Screaming Architecture), no por capa técnica genérica: el primer nivel de paquetes dice qué hace el sistema (`payment`, `provider`, `messaging`), no con qué framework está hecho. Dentro de `payment/`, que concentra la mayor cantidad de archivos, hay subcarpetas puramente organizativas (`service/`, `controller/`, `dto/`) — no representan capas de dependencia con reglas estrictas, solo agrupan por responsabilidad para mantener la carpeta navegable.

```text
relay/
├── payment/
│   ├── Payment.java
│   ├── PaymentStatus.java
│   ├── PaymentRepository.java
│   ├── PaymentResult.java
│   ├── ChargeRequestedEvent.java
│   ├── PaymentEventPublisher.java
│   ├── dto/
│   │   ├── CreatePaymentRequest.java
│   │   └── PaymentResponse.java
│   ├── service/
│   │   ├── PaymentService.java
│   │   ├── PaymentInsertService.java
│   │   ├── PaymentReconciliationService.java
│   │   └── PaymentChargeService.java
│   └── controller/
│       └── PaymentController.java
│
├── provider/
│   ├── PaymentProvider.java
│   ├── FakePaymentProvider.java
│   ├── ChargeRequest.java
│   ├── ChargeResult.java
│   ├── ChargeStatus.java
│   └── PaymentProviderTimeoutException.java
│
├── messaging/
│   ├── RabbitConfig.java
│   └── RabbitPaymentEventPublisher.java
│
└── shared/
    ├── config/
    │   └── JpaAuditingConfig.java
    └── error/
        ├── ErrorResponse.java
        └── GlobalExceptionHandler.java
```

`payment/` no depende directamente de RabbitMQ. La publicación de eventos se realiza mediante la interfaz `PaymentEventPublisher`, implementada por `RabbitPaymentEventPublisher` en `messaging/` — el dominio de pagos no importa nada de `org.springframework.amqp`.

## Flujo de un pago

```text
Client
  |
  | POST /payments
  v
PaymentController → PaymentService → PaymentInsertService
  |
  | Persist PENDING (REQUIRES_NEW)
  v
Database (commit)
  |
  | afterCommit
  v
RabbitMQ (payment.charge.queue)
  |
  v
PaymentChargeService
  |
  v
PaymentProvider
  |
  +---- SUCCESS ------> SUCCEEDED
  |
  +---- ACCEPTED -----> AWAITING_CONFIRMATION
  |                       |
  |                       | webhook del proveedor
  |                       v
  |                     SUCCEEDED / FAILED
  |
  +---- DECLINED -----> FAILED
  |
  +---- TIMEOUT ------> UNKNOWN
  |
  +---- SERVER_ERROR -> UNKNOWN
```

Estados:

```text
PENDING ──claim──> PROCESSING ──┬──> SUCCEEDED
                                ├──> FAILED
                                ├──> UNKNOWN
                                └──> AWAITING_CONFIRMATION ──webhook──> SUCCEEDED / FAILED
```

`PENDING` es "todavía no se cobró" y `AWAITING_CONFIRMATION` es "el proveedor aceptó
el cobro y la confirmación llega por webhook". Son estados distintos a propósito: la
guarda que toma un pago para cobrarlo es `status == PENDING`, así que meter los dos
en el mismo valor haría que se recobrara un pago que el proveedor ya aceptó.

`SUCCEEDED` y `FAILED` son terminales: ningún webhook tardío los revierte.

## Manejo de fallos

Los mensajes que no pueden procesarse correctamente no se reintentan indefinidamente. Relay utiliza:

- Reintentos limitados (3 intentos)
- Dead-letter exchange (`relay.payments.dlx`)
- Dead-letter queue
- Separación entre fallos recuperables (ej. commit todavía no visible) y fallos permanentes (ej. referencia a un pago inexistente)

Durante las pruebas se detectó un mensaje huérfano que fue redespachado más de 37.000 veces por ausencia de un límite de reintentos. El problema fue corregido mediante DLX y el límite de tres intentos.

## Stack

- Java 21
- Spring Boot
- Spring Data JPA / Hibernate
- SQL Server
- Flyway (migraciones versionadas)
- RabbitMQ / Spring AMQP
- Docker / Docker Compose
- JUnit, Spring Boot Test, Awaitility

## Cómo levantarlo

Requiere Docker Desktop y JDK 21.

### 1. Variables de entorno

```bash
cp .env.example .env
# editá .env: DB_PASSWORD (contraseña de SQL Server) y WEBHOOK_SECRET
```

Las dos son **obligatorias**: sin ellas la app no levanta y `mvn test` falla.
Docker Compose lee `.env` solo, pero **Spring Boot no**, así que hay que cargarlas
en la terminal desde la que corras Maven:

```bash
# bash / Git Bash
set -a && . ./.env && set +a
```

```powershell
# PowerShell
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $n, $v = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($n.Trim(), $v.Trim())
}
```

En IntelliJ no alcanza con el `.env`: van en la Run Configuration (tanto la de la
aplicación como la plantilla de JUnit), en *Environment variables*.

### 2. Infraestructura

```bash
docker compose up -d
```

Levanta SQL Server, RabbitMQ y `db-init`, un contenedor de un solo uso que espera
el healthcheck de la base, crea la base `relay` y termina (verificá que salió con
`docker compose ps -a`). Compose es solo infraestructura: la aplicación se corre
aparte.

### 3. Aplicación

```bash
mvn spring-boot:run   # o correrla desde el IDE
mvn test              # la suite completa
```

No hay ningún paso de DDL manual en ningún momento: `db-init` crea la base y
Flyway crea el esquema completo en el primer arranque. Sobre una DB que ya existe
de antes, Flyway la marca como baseline y no la toca.

API: `http://localhost:8080`
RabbitMQ Management: `http://localhost:15673`

**Gotchas conocidos:**
- Si tenés SQL Server o RabbitMQ nativos corriendo en Windows, van a chocar con los puertos por defecto de Docker. Este proyecto remapea SQL Server a `14330` y RabbitMQ a `5673`/`15673`. Si ves conflictos, verificá con `netstat -ano | findstr :PUERTO`.
- Antes de correr `PaymentChargeServiceIntegrationTest`, asegurate de no tener otra instancia de `RelayApplication` corriendo en paralelo — compite por los mismos mensajes de la cola y produce resultados inconsistentes.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/v1/oauth/token` | Emite un JWT a partir de `clientId` y `clientSecret` (flujo `client_credentials`). Público. 401 `invalid_client` si las credenciales no sirven, por el motivo que sea. |
| `POST` | `/payments` | Crea un pago y dispara su procesamiento asíncrono. Requiere `Authorization: Bearer` e `Idempotency-Key`. 201 si es nuevo, 200 si es replay del mismo body, 409 si la misma `Idempotency-Key` llega con un body distinto. |
| `GET` | `/payments/{id}` | Consulta un pago por ID. Requiere `Authorization: Bearer`. Devuelve 404 si pertenece a otro merchant. |
| `GET` | `/payments/needs-review` | Pagos del merchant que quedaron colgados y necesitan revisión manual: `UNKNOWN`, `PROCESSING` y `AWAITING_CONFIRMATION`, cada uno medido contra su propio umbral (`relay.reconciliation.stale-after` en `application.yaml`). Requiere `Authorization: Bearer` y filtra por el merchant del token. Parámetro opcional `olderThanMinutes`, que pisa los tres umbrales a la vez para una investigación puntual; sin él manda la configuración. |
| `POST` | `/webhooks/provider` | Recibe webhooks del proveedor. Requiere el header `X-Signature` con el HMAC-SHA256 del cuerpo crudo. 401 si la firma no valida, 200 tanto para evento nuevo como duplicado, 400 si el cuerpo no es parseable. |

El `merchantId` no es un parámetro de ningún endpoint: sale del claim `sub` del token
y de ningún otro lado. El header `X-Merchant-Id` ya no existe —era una afirmación del
cliente, así que el scope por merchant separaba datos pero no era una barrera de
seguridad—. Los tokens son HS256, duran 15 minutos y no hay refresh: cuando expira, el
cliente vuelve a pedir uno con sus credenciales.

Los merchants se dan de alta con un runner de línea de comandos, no por API; el
`clientSecret` se muestra una sola vez y no se puede recuperar.

## Estado actual

### Completado

- Creación y consulta de pagos
- Idempotencia bajo concurrencia real (test con `ExecutorService` + `CountDownLatch`)
- Persistencia de estados del pago
- Proveedor simulado (`FakePaymentProvider`) con los 5 resultados posibles
- Procesamiento asíncrono mediante RabbitMQ
- Publicación de eventos después del commit (`afterCommit` de `TransactionSynchronization`)
- Reintentos limitados + dead-letter queue
- Tests de integración con Awaitility cubriendo los 5 caminos de resultado
- Entorno reproducible con Docker
- Esquema versionado con Flyway (`ddl-auto: validate`, sin DDL manual)
- Reconciliación — job programado que **pregunta, no reintenta**. Por cada pago colgado en `UNKNOWN`, `PROCESSING` o `AWAITING_CONFIRMATION` consulta `PaymentProvider.getPaymentStatus(paymentId)` y escribe el desenlace: `SUCCEEDED` y `FAILED` cierran el pago, `PENDING` lo pasa a `AWAITING_CONFIRMATION`, y `NOT_FOUND` autoriza `FAILED` **solo** pasada una ventana de gracia de 30 minutos desde el intento de cobro. Un `UNKNOWN` del proveedor no autoriza nada y **no escribe la fila**, para que el pago no salga de su propia ventana de staleness por haber sido mirado. Nunca llama a `charge()`: un pago colgado es, por definición, uno del que no se sabe si movió plata. Pasadas 24 horas desde el intento se deja de consultar y el pago queda para una persona en `GET /payments/needs-review`.
- Webhooks — ingesta con verificación HMAC antes del 200, procesamiento asíncrono, idempotencia de eventos duplicados y guarda de estados terminales
- Autenticación machine-to-machine (`client_credentials`) y aislamiento entre merchants — el `merchantId` sale del token y no de un header

### Próximo

**Cierre de la mensajería** — lo que falta no es construirla sino demostrarla: un test de un
mensaje que efectivamente termina en la DLQ, poder saber cuántos hay ahí y poder inspeccionar
uno, y resolver si los webhooks entrantes tienen o no protección contra replay por timestamp.

### Más adelante

- Integración con un proveedor real en modo test (ej. Stripe test mode) — validación de que la abstracción `PaymentProvider` realmente desacopla el dominio de la infraestructura del proveedor
- CI/CD con GitHub Actions
- Documentación OpenAPI
- Logs estructurados y observabilidad básica

## Notas de desarrollo

El esquema está versionado con Flyway y `ddl-auto` quedó en `validate`: Hibernate verifica que la DB coincida con las entidades, pero no la modifica. Se migró después de acumular dos rondas de DDL manual que `ddl-auto: update` no sabía aplicar —agregar `version NOT NULL` a una tabla con filas, y actualizar el CHECK constraint de `status` al sumar un estado al enum—. La segunda rompió tres tests en silencio: el CHECK viejo rechazaba el estado nuevo y el cobro quedaba trabado en `PROCESSING`.

El objetivo del proyecto no es implementar un sistema de pagos completo ni competir con un payment processor. El objetivo es explorar los problemas de ingeniería que aparecen al orquestar operaciones de pago entre un comercio y proveedores externos: consistencia, idempotencia, concurrencia, procesamiento asíncrono y recuperación ante fallos.
