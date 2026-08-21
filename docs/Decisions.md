# Decisiones de arquitectura

## 001 — Monolito modular sobre microservicios
**Contexto**: Relay orquesta pagos entre un comercio y proveedores externos.
**Decisión**: monolito modular.
**Razón**: el problema no requiere distribución del código; introducirla aumentaría
complejidad operacional sin aportar valor al MVP.
**Consecuencias**: los límites entre módulos se sostienen por disciplina
(imports, visibilidad package-private), no por red.

## 002 — Idempotencia con scope (merchantId, idempotencyKey)
**Contexto**: dos requests simultáneos con la misma key no deben crear dos pagos.
**Decisión**: unique constraint sobre el par, no sobre `idempotencyKey` sola.
**Razón**: el idempotency-key es un contrato entre un merchant y Relay, no un
identificador global. Con scope global, un merchant quedaría a merced de las
keys que genere otro.
**Consecuencias**: la constraint de DB es la garantía real bajo concurrencia;
el `SELECT` previo es solo optimización del camino feliz.

## 003 — SERVER_ERROR y TIMEOUT mapean a UNKNOWN, no a FAILED
**Contexto**: el proveedor puede fallar sin decirnos si el cobro se procesó.
**Decisión**: ambos van a `UNKNOWN`.
**Razón**: un error del proveedor no indica en qué punto del proceso ocurrió.
Asumir `FAILED` arriesgaría reintentar un cobro que ya se hizo.
**Consecuencias**: `UNKNOWN` requiere resolución posterior (webhook o revisión manual),
no se resuelve solo.

## 004 — Publicación de eventos tras el commit
**Contexto**: publicar dentro de la transacción del INSERT permitía que el consumer
recibiera el evento antes de que el pago fuera visible en la DB.
**Decisión**: registrar una `TransactionSynchronization` con
`TransactionSynchronizationManager.registerSynchronization(...)` y publicar al broker
dentro de `afterCommit()`. No se usa `ApplicationEventPublisher` con
`@TransactionalEventListener(AFTER_COMMIT)`: la semántica es equivalente, el mecanismo no,
y esta decisión describió el mecanismo equivocado hasta que se contrastó contra el código.
**Razón**: dual-write race detectado mediante testing de integración.
**Consecuencias**: si el proceso muere entre el commit y el publish, el evento se pierde y
el pago queda en `PENDING` sin que nadie lo cobre. **No lo cubre la reconciliación**: el
job mira `UNKNOWN`, `PROCESSING` y `AWAITING_CONFIRMATION`, y `PENDING` queda deliberadamente
afuera porque ahí todavía no lo tomó nadie. Cerrarlo requiere un outbox.

## 005 — Reconciliación escala a humano, no reintenta
**Contexto**: pagos varados sin resolver, originalmente solo los `UNKNOWN` sin
`providerTransactionId`; desde la decisión 013 también `PROCESSING` y
`AWAITING_CONFIRMATION`.
**Decisión**: el job detecta y loguea; no reintenta el cobro.
**Razón**: sin API de consulta de estado del proveedor, reintentar arriesga doble cobro.
Es el mismo patrón que usan Stripe/Adyen: reconciliación automática donde hay fuente
de verdad consultable, escalación manual donde no la hay.

## 006 — Webhooks: firma antes del 200, negocio después
**Contexto**: los proveedores reintentan agresivamente ante timeouts, pero aceptar
sin validar expone la cola a basura.
**Decisión**: verificación HMAC (criptográfica, sin I/O) antes de responder;
validación de negocio en el consumer.
**Razón**: separa autenticación barata de validación costosa.

## 007 — Webhooks no modifican estados terminales
**Contexto**: los webhooks no llegan en orden garantizado.
**Decisión**: si el pago ya está SUCCEEDED/FAILED, el consumer no lo toca
(marca el evento PROCESSED y loguea WARN).
**Razón**: un evento tardío podría revertir un pago ya resuelto.

## 008 — DLX separado para webhooks
**Decisión**: `relay.webhooks.dlx` propio, no compartido con charge.
**Razón**: los dead-letters de charge (dinero no cobrado) y de webhook (estado no
sincronizado) son fallos de naturaleza distinta y requieren respuesta operativa distinta.

## 009 — Claim-then-call: la llamada al proveedor fuera de la transacción
**Contexto**: el listener de charge era una sola transacción que envolvía la llamada
al proveedor. El `PROCESSING` se flusheaba pero se pisaba antes del commit, así que
ningún observador externo lo veía nunca y no servía de guarda contra reentregas; y
se sostenía un lock de fila y una conexión del pool durante una llamada de red a un
tercero.
**Decisión**: `PaymentChargeTransactionService` con `claim(...)` y `recordResult(...)`,
ambos `REQUIRES_NEW` en un bean separado del listener. El listener pierde el
`@Transactional` y queda como orquestador: claim → cobro fuera de transacción →
recordResult.
**Razón**: el `PROCESSING` commiteado *antes* del cobro es lo que convierte a la fila
en la guarda real contra reentregas, y las dos transacciones cortas no retienen nada
mientras se espera al proveedor.
**Consecuencias**: si el proveedor lanza algo que no sea `PaymentProviderTimeoutException`,
la excepción sube sin manejar y el pago queda commiteado en `PROCESSING`; los reintentos
del listener salen por la guarda del claim en vez de recobrar. Es deliberado —preferimos
un pago trabado a un cobro duplicado— y desde la decisión 013 la reconciliación al menos
lo ve y lo escala, aunque sigue sin resolverlo automáticamente.

## 010 — AWAITING_CONFIRMATION como estado propio, no como PENDING
**Contexto**: `ChargeStatus.ACCEPTED` (el proveedor se hace cargo del cobro y confirma
después por webhook) necesitaba un estado destino. Mapearlo a `PENDING` era lo barato.
**Decisión**: estado propio `PaymentStatus.AWAITING_CONFIRMATION`.
**Razón**: `PENDING` significa "todavía no se cobró" y `ACCEPTED` significa lo contrario,
"el cobro está en manos del proveedor". La guarda que toma un pago para cobrarlo es
`status == PENDING`, así que sobrecargar ese valor con los dos sentidos haría que un
consumer recobrara un pago que el proveedor ya aceptó.
**Consecuencias**: es el primer estado no terminal desde el que un webhook puede resolver
un pago, así que la ruta feliz del webhook pasó a ser alcanzable end-to-end y los tests
dejaron de sembrar pagos a mano. Requirió DDL sobre el CHECK constraint de `status`, que
`ddl-auto: update` no actualizaba — el detonante de la decisión 011. Un pago que queda
esperando un webhook que nunca llega lo detecta la reconciliación desde la decisión 013,
con un umbral propio: esa espera es legítima por horas y no se mide como las otras.

## 011 — Esquema versionado con Flyway, `ddl-auto` en `validate`
**Contexto**: `ddl-auto: update` generaba el esquema, pero no cubre todo. Se acumularon
dos rondas de DDL manual: no agrega `version NOT NULL` a una tabla con filas, y no
actualiza el CHECK constraint que Hibernate genera sobre `status` al sumar un valor al
enum. Lo segundo rompió tres tests **en silencio** —sin error de arranque, el cobro
fallaba al persistir y el pago quedaba trabado en `PROCESSING`—.
**Decisión**: Flyway con una única `V1__initial_schema.sql` que consolida el esquema
actual, `baseline-on-migrate: true` con `baseline-version: 1`, y `ddl-auto: validate`.
**Razón**: el problema no fue que `update` no supiera aplicar un cambio, sino que no
avisara que no lo había aplicado. Versionado, agregar un estado es editar una migración;
y si alguien se olvida, `validate` lo frena en el arranque en vez de dejarlo aparecer en
runtime. V1 se generó desde el esquema real de la DB de dev, no desde las `@Entity`: la
DB es la que tenía los parches aplicados.
**Consecuencias**: una sola V1 en vez de la historia de cambios reconstruida —nadie fuera
de la máquina de desarrollo tuvo las versiones intermedias—. Sobre una DB preexistente
Flyway baselinea en 1 y no la toca; sobre una vacía corre V1 completa. `baseline-version: 0`
no sirve acá: dejaría el esquema en 0 y Flyway intentaría crear tablas que ya existen.
Los PK y CHECK pasan a tener nombres estables (`pk_payments`, `ck_payments_status`) en vez
de los autogenerados, así que una DB creada desde V1 y la de dev baselineada difieren en
esos nombres —`validate` no los mira—.

## 012 — `recordResult` no pisa un estado terminal, pero sí completa la correlación
**Contexto**: con claim-then-call (decisión 009), entre el `claim` y el `recordResult` la
fila queda commiteada en `PROCESSING` y sin lock durante toda la llamada al proveedor, y
`recordResult` escribía `finalStatus` sin mirar el estado actual. `@Version` no cubre esa
ventana: `recordResult` carga la entidad fresca en su propia transacción y la escribe en
el acto, así que no hay versión vieja con la que chocar. En el diseño anterior —una sola
transacción que envolvía el cobro— la escritura final reusaba la entidad cargada al
principio y el optimistic locking sí detectaba la escritura ajena; el refactor se llevó
esa detección sin reemplazarla.
**Decisión**: `recordResult` relee el estado y, si el pago ya está en `SUCCEEDED` o
`FAILED`, **no escribe el status**: loguea un WARN con `paymentId`, estado actual y estado
que intentaba aplicar. Mismo criterio y mismo formato que `WebhookProcessingService`
(decisión 007), en la dirección opuesta. El `providerTransactionId`, en cambio, **sí se
persiste aunque el status se descarte**, siempre que el pago no tenga uno ya.
**Razón**: la protección que hoy existe es indirecta y no es una guarda. El webhook
correlaciona por `provider_transaction_id`, que recién se escribe en `recordResult`, así
que mientras el cobro está en vuelo el webhook no encuentra el pago: es una ventana de
tiempo, no un invariante, y se cae apenas el id del proveedor se conozca antes
(correlación por otra clave, resolución manual, un provider que devuelva el id al iniciar
el cobro). El status y el id de transacción son datos con dueños distintos: del desenlace
manda quien llegó primero a terminal, pero el id es la única clave con la que el pago se
ata al proveedor. Descartarlo por una guarda que es sobre el status dejaría un pago
terminal permanentemente sin correlación —sin nada con qué atarlo a un webhook posterior,
a una disputa o a una conciliación manual— y eso es perder información sin ganar nada. Uno
existente no se pisa por el mismo motivo por el que no se pisa el status: si ya hay un id
guardado, es el que alguien usó para correlacionar.
**Consecuencias**: el invariante "un estado terminal no se revierte" deja de depender del
timing y pasa a estar escrito en los dos lados que escriben la fila. La guarda no es
alcanzable con el flujo actual, así que el test que la cubre
(`terminalStatusWrittenMidCharge_isNotOverwrittenByTheChargeResult`) fuerza la carrera a
mano: deja el pago en `PROCESSING`, lo resuelve con `JdbcTemplate` desde el hilo del test
mientras el cobro está frenado en el seam del provider, y verifica que el terminal quede
en pie. `@Version` sigue en la entidad y sigue cubriendo escrituras concurrentes de
verdad, pero no es lo que protege esta carrera.

## 013 — Reconciliación de los tres estados que se cuelgan, con umbral por estado
**Contexto**: el job miraba solo `UNKNOWN`. Otros dos estados pueden quedarse colgados sin
que nada los levante: `PROCESSING`, cuando el consumer claimeó y murió antes del
`recordResult` —ya se observó en la práctica con el CHECK constraint desactualizado (ver
011)—, y `AWAITING_CONFIRMATION`, cuando el proveedor aceptó el cobro y el webhook nunca
llegó. Los tres son invisibles para la operación si nadie los mira.
**Decisión**: el job escanea los tres, cada uno contra su propio umbral configurable en
`relay.reconciliation.stale-after` (por defecto `unknown: 15m`, `processing: 2m`,
`awaiting-confirmation: 6h`), y emite **un WARN por estado** con el detalle de qué
investigar en cada caso. `GET /payments/needs-review` devuelve los tres, con el mismo
scope por merchant.
**Razón**: un umbral global no sirve porque los tres estados tardan cosas distintas por
diseño. `PROCESSING` dura lo que dura la llamada al proveedor —segundos—, así que a los
minutos ya no hay nadie del otro lado. `AWAITING_CONFIRMATION` está esperando un webhook
que puede tardar horas legítimamente, y alertar antes sería alertar sobre el flujo feliz;
una alerta que suena cuando no pasa nada deja de mirarse. El WARN se separa por estado
porque cada uno abre una investigación distinta: `UNKNOWN` no tiene
`providerTransactionId` y hay que buscar por `reference` contra el extracto del proveedor;
`PROCESSING` puede tenerlo o no según dónde murió el consumer, y su presencia dice si el
cobro llegó a completarse; `AWAITING_CONFIRMATION` siempre lo tiene y lo que falta es la
entrega del webhook.
**Consecuencias**: sigue sin reintentar nada, y para `PROCESSING` eso es deliberado y más
fuerte que en `UNKNOWN`: ese pago tiene una llamada al proveedor que pudo haber movido
plata, así que republicar el evento de charge sería recobrar a ciegas. La decisión 005
—reconciliación escala a humano, no reintenta— se extiende tal cual a los tres estados. La
consulta con scope del endpoint y la sin scope del job siguen separadas a propósito: son
dos lectores con permisos distintos, y unificarlas dejaría un solo lugar donde olvidarse
del scope. El listado del WARN está topeado en 20 pagos por línea; el conteo completo va
igual y el detalle sale por el endpoint.
