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

## 004 — Publicación de eventos tras el commit (AFTER_COMMIT)
**Contexto**: publicar dentro de la transacción del INSERT permitía que el consumer
recibiera el evento antes de que el pago fuera visible en la DB.
**Decisión**: `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`.
**Razón**: dual-write race detectado mediante testing de integración.
**Consecuencias**: si el proceso muere entre commit y publish, el evento se pierde
— cubierto parcialmente por el job de reconciliación.

## 005 — Reconciliación escala a humano, no reintenta
**Contexto**: pagos varados en UNKNOWN sin `providerTransactionId`.
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
un pago trabado a un cobro duplicado— pero hoy la reconciliación no levanta ese estado.

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
dejaron de sembrar pagos a mano. Requiere DDL: el CHECK constraint que genera Hibernate
sobre `status` no lo actualiza `ddl-auto: update` (ver sección 6 de
`docs/features/webhooks.md`). Un pago que queda esperando un webhook que nunca llega
tampoco lo levanta la reconciliación actual.
