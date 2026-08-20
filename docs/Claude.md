# Contexto para Claude Code

## Qué es este proyecto
Relay: payment orchestration gateway. Ver `README.md` para el problema que resuelve.

## Convenciones no negociables

**Arquitectura**: monolito modular, package-by-feature (Screaming Architecture).
El primer nivel de paquetes es dominio (`payment/`, `provider/`, `messaging/`, `webhook/`),
nunca capa técnica genérica (`domain/`, `application/`, `infrastructure/`).

**Aislamiento de módulos**: la regla es sobre la **salida**. Publicar un mensaje pasa
siempre por un puerto del dominio (`PaymentEventPublisher`), implementado en `messaging/`
(`RabbitPaymentEventPublisher`): ningún módulo de dominio arma ni envía mensajes por su
cuenta. La **entrada** es al revés y a propósito: los listeners viven en el dominio
—`PaymentChargeService` y `WebhookProcessingService` llevan `@RabbitListener`—, así que
`payment/` y `webhook/` sí importan `org.springframework.amqp.rabbit.annotation`.
Consumir un evento *es* el caso de uso; un adapter en `messaging/` que solo delegara al
dominio sería una capa sin contenido.
Verificable con:
`grep -rn "org.springframework.amqp" src/main/java/io/github/alvxro12/relay/payment/`
Lo esperado es un único hit: el import de `RabbitListener` en `PaymentChargeService`.
Cualquier otro símbolo de amqp —`RabbitTemplate`, `Message`, `Queue`, converters— es una
fuga de infraestructura y va a `messaging/`.

**Eventos**: se publican **después del commit**, nunca dentro de la transacción que crea
el estado, con `TransactionSynchronizationManager.registerSynchronization(...)` y la
publicación adentro de `afterCommit()` (ver `PaymentInsertService` y
`WebhookEventInsertService`). No se usa `ApplicationEventPublisher` ni
`@TransactionalEventListener`: la semántica es la misma, el mecanismo no. Razón:
dual-write race documentado en `docs/Decisions.md` (decisión 004).

**Transacciones**: `@Transactional(REQUIRES_NEW)` siempre en clase separada.
La auto-invocación (`this.metodo()`) saltea el proxy de Spring y la anotación no aplica.

**Tests**: integración con Awaitility para flujos async, nunca `Thread.sleep`.
Beans singleton mutables (ej. `FakePaymentProvider`) se resetean en `@BeforeEach`.

**Secretos**: nunca hardcodeados. Variables de entorno vía `${VAR}` en `application.yaml`,
declaradas en `.env` (gitignored) y documentadas en `.env.example`.

## Gotchas del entorno
- SQL Server en puerto host `14330`, RabbitMQ en `5673`/`15673` (conflicto con servicios nativos de Windows).
- Antes de correr tests de integración: no debe haber otra instancia de `RelayApplication` corriendo (compite por los mensajes de la cola).
- `DB_PASSWORD` debe estar seteada en tres lugares independientes: `.env` (Docker), Run Config de Maven en IntelliJ, y plantilla de JUnit en IntelliJ.

## Antes de implementar
Si encontrás una decisión que afecte arquitectura, dominio, datos, seguridad,
consistencia, concurrencia, mensajería o comportamiento ante fallos, planteala
en vez de resolverla. Todo lo demás (nombres, estructura de clases, boilerplate,
configuración estándar de Spring), ejecutalo con una convención razonable.
