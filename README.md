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

Los eventos de pago se publican después del commit de la transacción, mediante `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`.

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
  | AFTER_COMMIT
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
  +---- DECLINED -----> FAILED
  |
  +---- TIMEOUT ------> UNKNOWN
  |
  +---- SERVER_ERROR -> UNKNOWN
```

Estados: `PENDING` → `PROCESSING` → `SUCCEEDED` / `FAILED` / `UNKNOWN`

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
- RabbitMQ / Spring AMQP
- Docker / Docker Compose
- JUnit, Spring Boot Test, Awaitility

## Cómo levantarlo

Requiere Docker Desktop.

```bash
cp .env.example .env
# editá .env con tu propia contraseña de SQL Server

docker compose up -d db rabbitmq
mvn spring-boot:run
```

API: `http://localhost:8080`
RabbitMQ Management: `http://localhost:15673`

**Gotchas conocidos:**
- Si tenés SQL Server o RabbitMQ nativos corriendo en Windows, van a chocar con los puertos por defecto de Docker. Este proyecto remapea SQL Server a `14330` y RabbitMQ a `5673`/`15673`. Si ves conflictos, verificá con `netstat -ano | findstr :PUERTO`.
- Antes de correr `PaymentChargeServiceIntegrationTest`, asegurate de no tener otra instancia de `RelayApplication` corriendo en paralelo — compite por los mismos mensajes de la cola y produce resultados inconsistentes.

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/payments` | Crea un pago y dispara su procesamiento asíncrono. Requiere `Idempotency-Key` y `X-Merchant-Id`. |
| `GET` | `/payments/{id}` | Consulta un pago por ID. Devuelve 404 si pertenece a otro merchant. |

`X-Merchant-Id` es un placeholder temporal hasta implementar autenticación real.

## Estado actual

### Completado

- Creación y consulta de pagos
- Idempotencia bajo concurrencia real (test con `ExecutorService` + `CountDownLatch`)
- Persistencia de estados del pago
- Proveedor simulado (`FakePaymentProvider`) con los 4 resultados posibles
- Procesamiento asíncrono mediante RabbitMQ
- Publicación de eventos después del commit (`AFTER_COMMIT`)
- Reintentos limitados + dead-letter queue
- Tests de integración con Awaitility cubriendo los 4 caminos de resultado
- Entorno reproducible con Docker

### Próximo

**Reconciliación** — job programado para resolver pagos que permanecen en `UNKNOWN`.

**Webhooks** — recepción de confirmaciones del proveedor, procesamiento asíncrono, idempotencia de eventos duplicados, integración con el flujo de reconciliación.

### Más adelante

- Integración con un proveedor real en modo test (ej. Stripe test mode) — validación de que la abstracción `PaymentProvider` realmente desacopla el dominio de la infraestructura del proveedor
- CI/CD con GitHub Actions
- Migraciones versionadas con Flyway (reemplazo de `ddl-auto`)
- Autenticación real (reemplazo de `X-Merchant-Id`)
- Documentación OpenAPI
- Logs estructurados y observabilidad básica

## Notas de desarrollo

Durante el desarrollo se utiliza `ddl-auto` para agilizar la creación del esquema en el entorno local. La migración a Flyway está contemplada para evitar depender de la generación automática del esquema.

El objetivo del proyecto no es implementar un sistema de pagos completo ni competir con un payment processor. El objetivo es explorar los problemas de ingeniería que aparecen al orquestar operaciones de pago entre un comercio y proveedores externos: consistencia, idempotencia, concurrencia, procesamiento asíncrono y recuperación ante fallos.