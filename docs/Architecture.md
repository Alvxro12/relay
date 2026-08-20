# Arquitectura

## Módulos
- `payment/` — dominio de pagos: entidad, repositorio, servicios, controller, eventos
- `provider/` — abstracción de proveedor + implementación simulada
- `messaging/` — infraestructura RabbitMQ (exchanges, colas, bindings, DLX)
- `webhook/` — recepción y procesamiento de eventos del proveedor
- `shared/` — configuración transversal, manejo global de errores

## Reglas de dependencia
- `payment/` → `provider/` (usa la interfaz `PaymentProvider`)
- `payment/` → NO `messaging/` (usa `PaymentEventPublisher`, implementada en `messaging/`)
- `messaging/` → `payment/` (implementa las interfaces del dominio)