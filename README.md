# Relay

Payment orchestration gateway para e-commerce. Java 21 · Spring Boot · SQL Server · JPA/Hibernate · Docker

## El problema

Un comercio que quiere aceptar pagos mediante distintos proveedores externos (Stripe, PayU, etc.) normalmente tiene que integrarse individualmente con cada uno — cada proveedor tiene su propia API, sus propios formatos de respuesta y sus propios webhooks.

Relay centraliza esa integración: el comercio se integra una sola vez con la API de Relay, y Relay coordina con el proveedor correspondiente por detrás. Relay no procesa pagos ni mueve dinero — esa responsabilidad sigue siendo del proveedor externo (que tiene la licencia regulatoria y la infraestructura bancaria). Lo que Relay resuelve es todo lo que rodea esa transacción: mantener un estado consistente incluso ante timeouts, respuestas ambiguas, webhooks duplicados, reintentos y fallos de red.

## Problemas que busco solucionar

- **Idempotencia real bajo concurrencia** — dos requests simultáneos con la misma `Idempotency-Key` no deben producir dos pagos, incluso si llegan al mismo instante exacto. Esto está probado con un test de concurrencia real, no simulado.
- **Estado `UNKNOWN`** — si Relay pierde la respuesta de un proveedor (timeout), no asume éxito ni fallo. Representa la incertidumbre explícitamente en vez de adivinar.
- **Manejo transaccional correcto** — evitar auto-deadlocks al recuperarse de una violación de constraint bajo carrera, sin dejar locks retenidos indefinidamente.

## Arquitectura

Monolito modular (no microservicios) — el problema no requiere distribución del código, y agregarla aumentaría complejidad operacional sin aportar valor al MVP.

```
relay/
├── payment/       # dominio de pagos: entidad, repositorio, servicio, controller
└── shared/        # configuración transversal, manejo global de errores
```

## Stack
- Java 21 + Spring Boot
- SQL Server (JPA/Hibernate)
- Docker / Docker Compose
- JUnit + Spring Boot Test

## Cómo levantarlo

Requiere Docker Desktop corriendo.

```bash
cp .env.example .env
# editá .env con tu propia contraseña de SQL Server

docker compose up db
mvn spring-boot:run
```

La API queda disponible en `http://localhost:8080`.

## Endpoints (v0.1)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/payments` | Crea un pago. Requiere headers `Idempotency-Key` y `X-Merchant-Id` (placeholder temporal hasta implementar auth real). |
| `GET` | `/payments/{id}` | Consulta un pago por ID. Devuelve 404 si pertenece a otro merchant. |

## Plan de acción (roadmap)
- Crear y consultar pagos
- Idempotencia garantizada, incluso bajo condiciones de carrera
- Persistencia de estados (`PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`)
- Tests de concurrencia con evidencia real
- Entorno reproducible con Docker

**🔜 Próximo — Proveedor simulado**
- `FakePaymentProvider` controlable (SUCCESS, DECLINED, TIMEOUT, SERVER_ERROR)
- Disparar el primer intento de cobro vía RabbitMQ

**🔜 Después — Webhooks**
- Recepción y procesamiento asíncrono vía RabbitMQ
- Idempotencia de eventos duplicados
- Dead-letter queue para reintentos fallidos

**🔜 Más adelante**
- CI/CD (GitHub Actions)
- Migraciones versionadas (Flyway) en reemplazo de `ddl-auto` inicialmente usé ddl-auto en entorno dev para agilizar la creacion de las tablas, soy consciente de migrar esto a None y usar versionados.
- Autenticación real (reemplazo del header `X-Merchant-Id`) "Implementacion de un Auth avanzado que ya tengo construido"
- Documentación OpenAPI
- Logs estructurados y observabilidad básica
```

