# Feature: Webhooks del proveedor

Última pieza del MVP. Endpoint que recibe webhooks del proveedor de pagos,
los persiste y los procesa de forma asíncrona para actualizar el `Payment`
correspondiente.

Rama: `feature/async-charge-processing`
Base: `9f27cc4 feat(payment): add reconciliation job for stale UNKNOWN payments`

---

## 1. Flujo

El endpoint separa **autenticación** (barata, antes del 200) de **validación de
negocio** (cara, después del 200):

```
POST /webhooks/provider
  │
  ├─ 1. Verificar HMAC-SHA256 del cuerpo crudo
  │     └─ inválida ──> 401. No toca la DB ni la cola.
  │
  ├─ 2. Extraer el id del evento y persistir WebhookEvent (status=RECEIVED)
  │     └─ provider_event_id duplicado ──> 200, sin reinsertar ni republicar
  │
  ├─ 3. Responder 200  (el proveedor reintenta agresivamente ante timeouts)
  │
  └─ 4. afterCommit ──> publicar a relay.payments / webhook.received
                          │
                          v
              webhook.processing.queue
                          │
                          v
        WebhookProcessingService (validación de negocio)
          ├─ correlacionar Payment por provider_transaction_id
          ├─ aplicar el cambio de estado
          └─ marcar el WebhookEvent como PROCESSED
```

### Contrato del payload

```json
{
  "id": "evt_123",
  "type": "payment.succeeded",
  "data": { "providerTransactionId": "txn_456" }
}
```

- `type`: `payment.succeeded` → `SUCCEEDED`, `payment.failed` → `FAILED`.
  Cualquier otro valor deja el evento en `FAILED` sin reintentar.
- Firma: header `X-Signature`, HMAC-SHA256 hex del cuerpo crudo, comparación
  en tiempo constante (`MessageDigest.isEqual`).
- Los campos desconocidos se ignoran: los proveedores agregan campos sin avisar.

---

## 2. Archivos nuevos

### `webhook/` — 16 archivos, ~590 LOC

Mismo criterio de subcarpetas que `payment/`: raíz para el dominio,
`controller/`, `dto/` y `service/` por volumen.

| Archivo | Qué hace |
|---|---|
| `WebhookEvent.java` | Entidad. `id` (UUID), `providerEventId` (unique), `rawPayload` (NVARCHAR(MAX)), `receivedAt`, `processedAt` (nullable), `status`. Factory `received(...)` y transiciones `markProcessed()` / `markFailed()`. |
| `WebhookEventStatus.java` | `RECEIVED`, `PROCESSED`, `FAILED`. |
| `WebhookEventRepository.java` | `existsByProviderEventId`, `findByProviderEventId`. |
| `WebhookReceivedEvent.java` | Mensaje de la cola. Lleva solo `webhookEventId` + `providerEventId`, **no** el payload: el cuerpo crudo ya está en la DB y esa es la única fuente de verdad, así el mensaje y la DB no pueden divergir. |
| `WebhookEventPublisher.java` | Puerto de salida. El dominio no conoce el broker, igual que `PaymentEventPublisher`. |
| `PaymentNotCorrelatedException.java` | No hay `Payment` para ese `providerTransactionId`. Puede ser transitorio, por eso se propaga para que el interceptor reintente. |
| `controller/WebhookController.java` | `POST /webhooks/provider`. Recibe el cuerpo como `String` crudo a propósito: el HMAC se calcula sobre los bytes exactos del proveedor, y deserializar a DTO y reserializar los cambiaría (orden de claves, espacios). |
| `dto/ProviderWebhookPayload.java` | Record del contrato de arriba, con `@JsonIgnoreProperties(ignoreUnknown = true)`. |
| `service/WebhookSignatureVerifier.java` | HMAC-SHA256. Secreto desde `${relay.webhook.secret}`, nunca hardcodeado. Expone `sign()` además de `isValid()`. |
| `service/WebhookIngestService.java` | Extrae el id del evento, chequea duplicado y delega el insert. Devuelve `WebhookIngestOutcome`. |
| `service/WebhookIngestOutcome.java` | `ACCEPTED` / `DUPLICATE` / `UNPARSEABLE`. Mismo estilo que `PaymentResult.outcome()`. |
| `service/WebhookEventInsertService.java` | `REQUIRES_NEW` + publicación en `afterCommit`, calcado de `PaymentInsertService`. |
| `service/WebhookProcessingService.java` | El consumer. `@RabbitListener` sobre `webhook.processing.queue` con `containerFactory = "webhookListenerContainerFactory"`. |
| `service/WebhookFailureService.java` | Marca `FAILED` desde fuera de la transacción del consumer (que ya hizo rollback). Lo usa el recoverer. |

### `messaging/` — 2 archivos

| Archivo | Qué hace |
|---|---|
| `RabbitWebhookEventPublisher.java` | Implementa `WebhookEventPublisher` sobre `RabbitTemplate`. Espejo de `RabbitPaymentEventPublisher`. |
| `WebhookProcessingRecoverer.java` | Se ejecuta cuando se agotaron los reintentos: deja el `WebhookEvent` en `FAILED` y después delega en `RejectAndDontRequeueRecoverer` para que el mensaje vaya a la DLQ. Si falla el marcado, loguea y manda el mensaje a la DLQ igual —perder el mensaje sería peor que perder la marca. |

### Tests — 1 archivo, 313 LOC

`src/test/java/io/github/alvxro12/relay/webhook/WebhookIntegrationTest.java`

---

## 3. Archivos modificados

| Archivo | Cambio |
|---|---|
| `payment/Payment.java` | `+ providerTransactionId` (nullable), `+ @Version`, `+ @Index` no único. |
| `payment/PaymentRepository.java` | `+ findByProviderTransactionId(String)`. |
| `payment/service/PaymentChargeService.java` | Persiste el `providerTransactionId` del `ChargeResult`. Comentario en la guarda de estado explicando que también cubre el reintento tras `OptimisticLockingFailureException`. **Después de esta feature** se refactorizó a claim-then-call: el listener quedó como orquestador sin transacción y las dos escrituras se movieron a `PaymentChargeTransactionService` (`REQUIRES_NEW`). |
| `messaging/RabbitConfig.java` | `+ webhook.processing.queue`, routing key `webhook.received`, `relay.webhooks.dlx` + DLQ, `webhookListenerContainerFactory`. |
| `provider/FakePaymentProvider.java` | Seams de test: `forceNextTransactionId(String)` y `onNextCharge(Runnable)` (one-shot, para reproducir la carrera). Campos a `volatile`. |
| `resources/application.yaml` | `+ relay.webhook.secret: "${WEBHOOK_SECRET}"`. |
| `.env` / `.env.example` | `+ WEBHOOK_SECRET`. |
| `docker-compose.yaml` | `+ WEBHOOK_SECRET` al servicio `app`. *(Ese servicio se eliminó después: nunca llegó a funcionar —apuntaba el contexto de build a `src/main/resources` y no había Dockerfile— y compose quedó solo con infraestructura.)* |
| `payment/PaymentChargeServiceIntegrationTest.java` | El `@BeforeEach` ahora también resetea los seams nuevos del provider. |

---

## 4. Topología de RabbitMQ

```
relay.payments (topic)
  ├─ payment.charge.requested ──> payment.charge.queue ──[x-DLX]──> relay.payments.dlx (fanout)
  │                                                                   └─> payment.charge.queue.dlq
  └─ webhook.received ─────────> webhook.processing.queue ──[x-DLX]──> relay.webhooks.dlx (fanout)
                                                                        └─> webhook.processing.queue.dlq
```

**Cola nueva sobre el exchange existente, DLX propio.** El motivo del DLX
separado no es técnico sino operativo: un dead-letter de charge significa plata
que no se cobró y uno de webhook significa estado que no se sincronizó. Son
incidentes de naturaleza distinta, con dueño y urgencia distintos, y quien opera
el sistema necesita poder revisarlos, alertarlos y drenarlos por separado.
Además, `relay.payments.dlx` es **fanout**: bindear una segunda DLQ ahí le
entregaría a cada cola *todos* los mensajes muertos, no solo los suyos.

**Reintentos.** Mismo límite que charge (3 intentos totales: 1 inicial + 2
reintentos) pero **con backoff** (500 ms, factor 2, tope 4 s), a diferencia de
charge que reintenta al instante. El fallo esperable acá es que el webhook
llegue antes de que el consumer de charge haya commiteado el
`providerTransactionId`; sin backoff los tres intentos se queman dentro de esa
misma ventana y mandaría a la DLQ un evento perfectamente procesable.

---

## 5. Decisiones de diseño

| # | Decisión | Motivo |
|---|---|---|
| 1 | **Correlación por `providerTransactionId`** en `Payment`, seteado por `PaymentChargeService` desde `ChargeResult`. Nullable. | Es la única clave que un proveedor real devuelve. `reference` es del merchant, nullable y sin unique: dos pagos pueden compartirla. Nullable porque `TIMEOUT` nunca lo recibe (excepción, sin `ChargeResult`) y `SERVER_ERROR` lo devuelve null por diseño. |
| 2 | **Índice NO único** sobre `provider_transaction_id`. | En SQL Server un `UNIQUE` trata los NULL como iguales y admite uno solo. La mayoría de las filas lo tienen en null. Haría falta un índice único filtrado. |
| 3 | **DLX propio** (`relay.webhooks.dlx`) en vez de reusar el de payments. | Ver sección 4. |
| 4 | **Pago no encontrado → reintentos con backoff, después DLQ + `FAILED`.** | Hay un caso transitorio legítimo (el webhook gana de mano al consumer de charge). Marcar `FAILED` de una perdería el evento; reintentar sin techo lo dejaría rebotando para siempre. |
| 5 | **Evento no correlacionable de raíz → `FAILED` inmediato, sin reintentos.** Cubre payload ilegible, `type` no soportado y `providerTransactionId` ausente. | Ningún reintento puede arreglar un payload que ya está guardado y es inválido. |
| 6 | **`@Version` en `Payment`** (optimistic locking). | Dos consumers escriben la misma fila. El que pierde una escritura concurrente recibe `OptimisticLockingFailureException`, el interceptor lo reprocesa y **al releer vuelve a pasar por las guardas de estado**, así que no pisa el resultado del otro. **Lo que no cubre es la ventana del claim-then-call**: entre el `claim` y el `recordResult` la fila queda commiteada y sin lock durante todo el cobro, y `recordResult` carga la entidad fresca en su propia transacción, así que no hay versión vieja con la que chocar. Esa carrera la protege la guarda de estado terminal en `recordResult` (decisión 012 de `docs/Decisions.md`), no el optimistic locking. |
| 7 | **Estados terminales no se modifican.** Si el pago ya está en `SUCCEEDED` o `FAILED`, el consumer no lo toca: marca el `WebhookEvent` como `PROCESSED` igual (para que no se reintente) y loguea un WARN con `paymentId`, estado actual y estado que el evento intentaba aplicar. `recordResult` aplica el mismo criterio en la dirección opuesta. | Los webhooks no llegan en orden garantizado. Dejar que un evento tardío revierta un estado terminal reactivaría un pago ya resuelto. Que la guarda esté en los dos lados es lo que hace que el invariante no dependa de quién escriba primero. |
| 8 | **Duplicado responde 200**, no 409. | Cualquier cosa que no sea 2xx haría que el proveedor lo siga reintentando. |
| 9 | **Doble barrera de idempotencia**: unique constraint en `provider_event_id` (ingesta) + guarda `status != RECEIVED` en el consumer (procesamiento). | La unique constraint es la que decide; el `existsBy...` previo es solo un atajo barato para el caso común. |

---

## 6. Migración de esquema

**El esquema ya no se genera ni se parchea a mano.** Está versionado con Flyway
en `src/main/resources/db/migration/V1__initial_schema.sql`, y `ddl-auto` quedó
en `validate`: Hibernate verifica que la DB coincida con las entidades pero no la
modifica. Una DB vacía la construye Flyway entera desde V1; una DB que ya existe
la marca como baseline en la versión 1 y no la toca.

El DDL manual que había acá quedó consolidado en esa V1. Lo que sigue es la razón
histórica, porque explica por qué se migró.

### Por qué `ddl-auto: update` no alcanzaba

Dos cosas que `update` no sabe hacer, encontradas de a una:

1. **No agrega `version NOT NULL` a una tabla que ya tiene filas.** Se resolvió
   con un `ALTER TABLE ... DEFAULT 0 WITH VALUES` a mano sobre la DB de dev.

2. **No actualiza el CHECK constraint que Hibernate genera sobre `status`.**
   Hibernate crea, a partir del enum, un constraint con nombre autogenerado
   (`CK__payments__status__…`) que enumera los valores válidos. Al agregar
   `AWAITING_CONFIRMATION` al enum, el constraint siguió teniendo los cinco
   valores viejos.

El segundo caso es el que justificó la migración, por *cómo* falló: no dio ningún
error de arranque. Todo cobro `ACCEPTED` fallaba recién al persistir el resultado,
la excepción subía sin manejar y —con el claim ya commiteado— el pago quedaba
trabado en `PROCESSING`. Se veía como tres tests que se colgaban esperando un
estado que nunca llegaba, no como un problema de esquema.

Versionado en V1, agregar un estado al enum es editar una migración. Y si alguien
se olvida, `validate` lo frena en el arranque en vez de dejarlo aparecer en runtime.

> El `columnDefinition` explícito de `raw_payload` también tenía su gotcha: cuando
> se declara uno, Hibernate lo usa tal cual y descarta el `nullable = false` del
> `@Column`, por eso el `NOT NULL` iba adentro del `columnDefinition`. En V1 la
> columna se declara directamente como `nvarchar(max) NOT NULL`.

Esquema resultante de `webhook_events`:

```
id                 uniqueidentifier  NOT NULL  (pk_webhook_events)
provider_event_id  varchar(255)      NOT NULL  (uk_webhook_provider_event_id, unique)
raw_payload        nvarchar(max)     NOT NULL
received_at        datetimeoffset(7) NOT NULL
processed_at       datetimeoffset(7) NULL
status             varchar(255)      NOT NULL  (ck_webhook_events_status)
```

---

## 7. Tests

`WebhookIntegrationTest` — mismo estilo y rigor que
`PaymentChargeServiceIntegrationTest`: Awaitility, sin `Thread.sleep`.

Usa **MockMvc construido a mano** sobre el `WebApplicationContext` en vez de
`webEnvironment = RANDOM_PORT`: `RANDOM_PORT` crearía un segundo contexto de
Spring y sus `@RabbitListener` competirían con los del contexto de los tests de
payment por las mismas colas, cada uno con su propio `FakePaymentProvider`. Con
`@SpringBootTest` pelado los cinco test classes comparten un único contexto.

| Test | Qué verifica |
|---|---|
| `invalidSignature_isRejectedWithoutTouchingDbOrQueue` | Firma forjada → 401. Sin header de firma → 401 (no el 422 de header faltante). `COUNT(*) = 0` en `webhook_events`. Como la publicación cuelga del `afterCommit` de ese insert, la ausencia de fila prueba que tampoco se encoló nada. |
| `validSignature_persistsEventAndConsumerUpdatesPayment` | 200, `WebhookEvent` persistido con el `rawPayload` byte a byte, y el consumer lleva el pago a `SUCCEEDED` + evento a `PROCESSED` con `processedAt` seteado. El pago sale del flujo real (`ACCEPTED` → `AWAITING_CONFIRMATION`), no sembrado. |
| `duplicateWebhook_isProcessedExactlyOnce` | Mismo pago real que el anterior. Dos POST idénticos, ambos 200. Conteo real vía `JdbcTemplate`: 1 fila. `processedAt` intacto tras el segundo (el consumer no volvió a correr) y `payment.version` subió **exactamente una vez**. |
| `webhookForUnknownPayment_endsInDlqAndMarkedFailed` | Evento `FAILED` en DB, 1 mensaje en la DLQ, y la cola principal queda vacía y **estable** (`await().during(3s)`) — no rebota. |
| `webhookArrivingMidCharge_doesNotClobberTheChargeResult` | La carrera del punto 6: el cobro se frena dentro del provider con un latch, entra un `payment.failed` en el medio, se libera el cobro. El pago queda `SUCCEEDED` (gana el resultado real) y el evento cierra `PROCESSED`. |

---

## 8. Evidencia de la corrida

`mvn test` — **15/15 en verde**, corridas consecutivas con resultados idénticos.
(Eran 12 al cerrar esta feature; los 3 que se sumaron son de `claim-then-call` y
de `ACCEPTED`.)

```
PaymentChargeServiceIntegrationTest   4/4
PaymentServiceConcurrencyTest         1/1
PaymentReconciliationJobTest          1/1
RelayApplicationTests                 1/1
WebhookIntegrationTest                5/5
------------------------------------------
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Estado final en la DB (3 corridas acumuladas)

```
caso        | webhook_status | payment_status | payment_version | corridas
------------|----------------|----------------|-----------------|---------
evt_valid   | PROCESSED      | SUCCEEDED      | 1               | 3
evt_dup     | PROCESSED      | SUCCEEDED      | 1               | 3
evt_race    | PROCESSED      | SUCCEEDED      | 2               | 3
evt_orphan  | FAILED         | (sin payment)  | -               | 3
evt_badsig  | (0 filas)      | -              | -               | -
```

Lectura de la columna `version`, que es el conteo real de escrituras sobre la fila:

- `evt_valid` = **1** → una sola escritura, `UNKNOWN → SUCCEEDED`.
- `evt_dup` = **1** → una sola escritura **pese a dos entregas**. Es la prueba
  del procesamiento único, no el status HTTP.
- `evt_race` = **2** → las dos escrituras del consumer de charge (`PROCESSING`,
  después `SUCCEEDED`) y **ninguna del webhook**, que intentaba dejarlo en
  `FAILED`. La guarda de estado terminal funcionó.

### Estado de las colas

```
cola                           ready  unacked  redeliver
payment.charge.queue           0      0        0
payment.charge.queue.dlq       0      0        0
webhook.processing.queue       0      0        0
webhook.processing.queue.dlq   1      0        0
```

- Nada atascado, `unacked = 0` en todas.
- `redeliver = 0` en todas: los 3 intentos son en memoria (retry interceptor),
  no rebotan por el broker.
- El `1` en `webhook.processing.queue.dlq` es el evento huérfano, que es su
  destino de diseño. Header `x-death: count=1, reason=rejected` → se
  dead-lleteró **exactamente una vez**, no rebotó.
- `payment.charge.queue.dlq` en **0** es la prueba directa de que los DLX
  separados funcionan: con el fanout compartido ese dead-letter habría caído
  también ahí.
- El `webhookEventId` del mensaje en la DLQ coincide exactamente con la fila
  `FAILED` en la DB, y su `raw_payload` está íntegro.

---

## 9. Pendiente / deuda conocida

**Resuelto:** la ruta feliz del webhook ahora es alcanzable end-to-end.

Se agregó `ChargeStatus.ACCEPTED` —el proveedor se hace cargo del cobro y
devuelve id de transacción, pero el resultado llega después— y el estado
`PaymentStatus.AWAITING_CONFIRMATION` al que mapea.

| `ChargeStatus` | Estado final | `providerTransactionId` | ¿El webhook puede aplicar? |
|---|---|---|---|
| `SUCCESS` | `SUCCEEDED` | seteado | No — estado terminal, WARN |
| `ACCEPTED` | `AWAITING_CONFIRMATION` | seteado | **Sí — es exactamente su caso de uso** |
| `DECLINED` | `FAILED` | seteado | No — estado terminal, WARN |
| `SERVER_ERROR` | `UNKNOWN` | **null** | No — no correlacionable |
| `TIMEOUT` | `UNKNOWN` | **null** | No — no correlacionable |

`AWAITING_CONFIRMATION` es un estado propio y no un `PENDING` porque significa lo
contrario: `PENDING` es "acá nadie cobró nada todavía" y `AWAITING_CONFIRMATION`
es "el cobro está en manos del proveedor". La guarda que toma un pago para
cobrarlo sigue siendo `status == PENDING`, así que sobrecargar ese valor con los
dos sentidos haría que un consumer recobrara un pago que el proveedor ya aceptó.

Las guardas de estado terminal (decisión 7) no cambian: terminal sigue siendo
`SUCCEEDED` o `FAILED`, y `AWAITING_CONFIRMATION` queda deliberadamente afuera.

Como consecuencia, `WebhookIntegrationTest` ya no siembra pagos a mano: los dos
tests que lo hacían ahora recorren el flujo real (`createPayment` → consumer de
charge → provider devolviendo `ACCEPTED`) para llegar al pago que el webhook
viene a cerrar.

### Todavía pendiente

- **`PaymentReconciliationJob` solo mira `UNKNOWN`.** Un pago que queda en
  `AWAITING_CONFIRMATION` porque el webhook prometido nunca llegó no lo levanta
  nadie. Es el mismo tratamiento que ya necesita `UNKNOWN`: detectarlo por
  antigüedad y marcarlo para revisión manual.
- **Un pago puede quedar trabado en `PROCESSING`.** Si el provider lanza algo que
  no sea `PaymentProviderTimeoutException`, la excepción sube sin manejar y, con
  el claim ya commiteado, el pago se queda en `PROCESSING`. Es deliberado
  —preferimos un pago trabado a un cobro duplicado, ver el comentario en
  `handleChargeRequested`— pero también depende de una reconciliación que hoy no
  cubre ese estado.

### Otros

- **No hay protección contra replay por ventana temporal.** La unique constraint
  sobre `provider_event_id` cubre la reentrega del mismo evento, pero un atacante
  con un payload firmado capturado podría reenviarlo indefinidamente si el id
  todavía no se vio. Un timestamp dentro del string firmado + rechazo por
  antigüedad lo cerraría.
- **`mvn test` necesita `WEBHOOK_SECRET` y `DB_PASSWORD` en el entorno.** Spring
  Boot no lee `.env` por sí solo. Desde bash: `set -a && . ./.env && set +a && mvn test`.
- El endpoint no tiene rate limiting ni autenticación de merchant — la firma HMAC
  es la única barrera, que es lo correcto para un webhook, pero conviene tenerlo
  presente cuando se agregue Spring Security al resto de la API.
