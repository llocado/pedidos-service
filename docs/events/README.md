# Eventos de pedidos-service

Documentacion de contrato para el bus de eventos (RabbitMQ), segun lo definido en `app-productos/docs/ROADMAP.md` (seccion "Contrato de eventos"). Cada evento va envuelto en el envelope comun (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `correlationId`, `payload`).

## Publica (productor)

- **`pedido.creado`** — [pedido.creado.schema.json](./pedido.creado.schema.json). Exchange `pedidos.events` (topic). Se publica cuando `CrearPedidoDesdeCarritoUseCase` crea un pedido nuevo. Consumido a futuro por `pagos-service`.

## Consume (consumidor)

- **`carrito.checkout-iniciado`** — [carrito.checkout-iniciado.schema.json](./carrito.checkout-iniciado.schema.json). Exchange `carrito.events` (topic), propiedad de `carrito-service`. **Este schema es una copia de referencia**: la fuente de verdad es `carrito-service/docs/events/` (publicado por `IniciarCheckoutUseCase` en `POST /api/carrito/checkout`). Si el schema publicado ahi difiere de esta copia, ese es el que manda -- actualizar este archivo para que coincida.

## Idempotencia

Todo listener de este servicio descarta un `eventId` ya visto usando la tabla `eventos_procesados` (ver `EventoProcesadoPort`), porque RabbitMQ entrega *at-least-once* y un mismo evento puede reentregarse tras un reintento.
