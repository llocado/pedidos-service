# pedidos-service

Microservicio de pedidos del proyecto supermercado. Arquitectura hexagonal,
mismo estilo que `productos-service` y `carrito-service`: casos de uso en
`application/usecase`, puertos en `application/port`, adaptadores en
`infrastructure/*`.

Ver `docs/ROADMAP.md` en el repo `app-productos` para el diseño completo del
proyecto (decisiones de arquitectura, mapa de servicios, contrato de eventos).

## Decisiones de diseño propias de este servicio

- **La creación de un pedido no es un endpoint REST.** Ocurre solo a partir
  del evento `carrito.checkout-iniciado` (ver `CarritoEventListener`). Este
  servicio solo expone lectura por HTTP (`GET /api/pedidos`, `GET /api/pedidos/{id}`).
- **`pedidos-service` no importa el dominio de `carrito-service` ni de
  `productos-service`.** `Pedido`/`ItemPedido` son su propio modelo, poblado
  desde el payload del evento — duplicación intencional, no un descuido (ver
  ROADMAP.md).
- **Sin estado terminal de rechazo.** Si el pago falla, el pedido simplemente
  queda en `CREADO` (decisión ya cerrada en el ROADMAP: alcanza con "pedido
  pendiente" para el alcance de este proyecto).
- **Idempotencia genérica por `eventId`.** Tabla `eventos_procesados`
  (`EventoProcesadoPort`), compartida por todos los listeners del servicio
  (checkout, `pago.aprobado` y `pago.rechazado`).
- **Los pagos cambian el pedido.** `pago.aprobado` lo pasa de CREADO a PAGADO
  (`MarcarPedidoPagadoUseCase`) tras comprobar que usuario y monto coinciden
  con los del pedido (si no, DLQ sin reintentos). Si el pedido todavía no es
  visible (`pedido.creado` se publica antes del commit), el evento se
  **reintenta** en vez de ir a la DLQ. `pago.rechazado` solo se registra.
- **Sin stock.** Si al descontar el stock de un pago aprobado algún producto no alcanza, `app-productos` publica
  `stock.descuento-rechazado` y el pedido pasa de PAGADO a `SIN_STOCK` (estado final, gestión manual). Si el evento
  se adelanta al `pago.aprobado` (el pedido sigue en CREADO) pasa igual a `SIN_STOCK` y el pago tardío se ignora.
- **`correlationId` de punta a punta.** El `correlationId` del checkout se
  propaga a `pedido.creado`, así se sigue el flujo entre servicios en los logs.
- **Primer servicio del proyecto en tocar RabbitMQ de verdad.** Declara
  (de forma defensiva e idempotente) el exchange `carrito.events`, que en
  rigor es propiedad de `carrito-service` (que también lo declara, con las
  mismas propiedades) — así la cola se puede bindear aunque `carrito-service`
  no haya arrancado todavía. Ver `docs/events/README.md`.
- **Política de errores del listener (nunca reencolar en bucle).** Un mensaje
  irrecuperable (JSON ilegible, payload inválido, regla de dominio violada) va
  a la DLQ de inmediato, sin reintentos. Un fallo transitorio (por ejemplo, la
  base de datos caída) se reintenta: 1 intento + 3 reintentos con esperas de
  1 s, 2 s y 4 s, y luego va a la DLQ. Antes un mensaje que fallaba se
  reencolaba sin fin (cientos de miles de líneas de log por segundo). Los logs
  llevan `eventId` y `correlationId`, nunca el payload completo. Para
  reprocesar la DLQ hay que revisarla en la UI de RabbitMQ
  (`pedidos.carrito-checkout-iniciado.dlq`).
- **El id de usuario nunca viene del cliente.** Siempre sale del claim `sub`
  del JWT validado por Spring Security.

## Requisitos para correr localmente

1. `supermercado-infra` levantado (Keycloak + RabbitMQ): `docker compose up -d`
   en ese repo.
2. Este repo tiene su propio Postgres (puerto **5434**, no choca con el 5432
   de `productos-service` ni el 5433 de `carrito-service`).

```bash
docker compose up -d          # Postgres de este servicio
./gradlew bootRun             # arranca en localhost:8082
```

El flujo completo se prueba con `carrito-service` corriendo: un
`POST /api/carrito/checkout` (carrito con items) publica
`carrito.checkout-iniciado` y este servicio crea el pedido (consultable con
`GET /api/pedidos`). Para probar este servicio de forma aislada, se puede
publicar el mensaje directamente en RabbitMQ (UI de management en
`localhost:15672`, exchange `carrito.events`, routing key
`carrito.checkout-iniciado`) — ver `docs/events/carrito.checkout-iniciado.schema.json`
para el payload esperado.

## Endpoints

Todos requieren `Authorization: Bearer <token>` con rol `cliente` (ver
`bruno/Pedidos/00 - Obtener Token.bru`).

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/api/pedidos` | Listar los pedidos del usuario autenticado |
| GET | `/api/pedidos/{id}` | Ver un pedido (404 si no existe o es de otro usuario) |

## Tests

```bash
./gradlew test      # unitarios (dominio, casos de uso)
./gradlew intTest    # integracion con Testcontainers (Postgres + RabbitMQ, requiere Docker)
```

El test de integración publica un evento real en un RabbitMQ de
Testcontainers y deja que `CarritoEventListener` lo consuma de punta a
punta (sin mockear nada del pipeline de mensajería), verificando que el
pedido queda persistido y es consultable por REST — incluye un caso de
reentrega del mismo evento para probar la idempotencia.
