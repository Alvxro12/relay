# Contexto para Claude Code

## Qué es este proyecto
Relay: payment orchestration gateway. Ver `README.md` para el problema que resuelve.

## Convenciones no negociables

**Arquitectura**: monolito modular, package-by-feature (Screaming Architecture).
El primer nivel de paquetes es dominio (`payment/`, `provider/`, `messaging/`, `webhook/`),
nunca capa técnica genérica (`domain/`, `application/`, `infrastructure/`).

**Aislamiento de módulos**: `payment/` no importa `org.springframework.amqp`.
La comunicación con mensajería pasa por la interfaz `PaymentEventPublisher`.
Verificable con: `grep -r "org.springframework.amqp" src/main/java/**/payment/`

**Eventos**: se publican con `@TransactionalEventListener(AFTER_COMMIT)`, nunca
dentro de la transacción que crea el estado. Razón: dual-write race documentado
en `docs/DECISIONS.md`.

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