# Eventos de pedidos-service

Documentacion de contrato para el bus de eventos (RabbitMQ), segun lo definido en `app-productos/docs/ROADMAP.md` (seccion "Contrato de eventos"). Cada evento va envuelto en el envelope comun (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `correlationId`, `payload`).

## Publica (productor)

- **`pedido.creado`** — [pedido.creado.schema.json](./pedido.creado.schema.json). Exchange `pedidos.events` (topic). Se publica cuando `CrearPedidoDesdeCarritoUseCase` crea un pedido nuevo. Consumido por `pagos-service`, que simula el pago. El `correlationId` es el del checkout de `carrito-service` y se propaga a este evento.

## Consume (consumidor)

- **`pago.aprobado`** — contrato en `pagos-service/docs/events/pago.aprobado.schema.json` (la fuente de verdad es el productor). Exchange `pagos.events`, cola `pedidos.pago-aprobado` (con `pedidos.pago-aprobado.dlq`). Pasa el pedido de CREADO a PAGADO. Antes comprueba que el `usuarioId` y el `monto` del pago coincidan con los del pedido: si no, es un dato incoherente y va a la DLQ sin reintentos. Si el pedido todavia no existe se **reintenta** (1 + 3 veces): `pedido.creado` se publica antes de confirmar el pedido en la base, asi que `pago.aprobado` puede llegar un instante antes de que el pedido sea visible. Si ya esta PAGADO (o mas adelante) se ignora.
- **`pago.rechazado`** — contrato en `pagos-service/docs/events/pago.rechazado.schema.json`. Cola `pedidos.pago-rechazado` (con su DLQ). Solo se registra en el log (el pedido sigue en CREADO) y se marca procesado.

- **`carrito.checkout-iniciado`** — [carrito.checkout-iniciado.schema.json](./carrito.checkout-iniciado.schema.json). Exchange `carrito.events` (topic), propiedad de `carrito-service`. **Este schema es una copia de referencia**: la fuente de verdad es `carrito-service/docs/events/` (publicado por `IniciarCheckoutUseCase` en `POST /api/carrito/checkout`). Si el schema publicado ahi difiere de esta copia, ese es el que manda -- actualizar este archivo para que coincida.

## Errores y reintentos

Un evento que no se puede procesar nunca se reencola sin limite. Si es irrecuperable (JSON ilegible, payload invalido, regla de dominio violada) va a la DLQ `pedidos.carrito-checkout-iniciado.dlq` de inmediato. Si el fallo es transitorio (por ejemplo, la base de datos no responde) se reintenta 3 veces tras el primer intento (esperas de 1 s, 2 s y 4 s) y luego va a la DLQ. Los logs llevan `eventId` y `correlationId`.

## Idempotencia

Todo listener de este servicio descarta un `eventId` ya visto usando la tabla `eventos_procesados` (ver `EventoProcesadoPort`), porque RabbitMQ entrega *at-least-once* y un mismo evento puede reentregarse tras un reintento.
