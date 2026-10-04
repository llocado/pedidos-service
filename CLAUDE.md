# pedidos-service

Convierte un carrito confirmado en un pedido; dueño del ciclo de vida del
pedido. Reglas compartidas con el resto del proyecto en `../CLAUDE.md` —
este archivo solo cubre lo específico de este repo.

Diseño completo del proyecto: `../app-productos/docs/ROADMAP.md`.

## Stack

- Spring Boot 4.1.0, Java 21, Gradle.
- Postgres 16 (contenedor `pedidos-postgres`, puerto **5434** — 5432 lo
  ocupa `productos-service`, 5433 `carrito-service`).
- RabbitMQ (vía `supermercado-infra`) — primer servicio del proyecto en
  tocarlo de verdad.
- Spring Security OAuth2 Resource Server contra Keycloak, igual que
  `carrito-service`.

## Decisiones de diseño propias de este servicio

- **La creación de un pedido no es un endpoint REST.** Ocurre solo a partir
  del evento `carrito.checkout-iniciado` (`CarritoEventListener`). Este
  servicio solo expone lectura por HTTP (`GET /api/pedidos`,
  `GET /api/pedidos/{id}`).
- No importa el dominio de `carrito-service` ni de `productos-service`:
  `Pedido`/`ItemPedido` son su propio modelo, poblado desde el payload del
  evento (duplicación intencional, ver ROADMAP).
- Sin estado terminal de rechazo: si el pago falla, el pedido queda en
  `CREADO` (alcanza con "pendiente" para el alcance del proyecto).
- Idempotencia genérica por `eventId` vía tabla `eventos_procesados`
  (`EventoProcesadoPort`), pensada para reutilizarse en listeners futuros
  (ej. `pago.aprobado`/`pago.rechazado`).
- Declara de forma defensiva e idempotente el exchange `carrito.events`,
  que en rigor es propiedad de `carrito-service` (que también lo declara) —
  así la cola se puede bindear aunque ese servicio no haya arrancado.
- El id de usuario nunca viene del cliente: siempre sale del claim `sub`
  del JWT validado por Spring Security.

## Comandos

```bash
docker compose up -d      # Postgres de este servicio
./gradlew bootRun          # arranca en localhost:8082
./gradlew test             # unitarios (dominio, casos de uso)
./gradlew intTest          # integración: Testcontainers (Postgres + RabbitMQ)
```

El test de integración publica un evento real en RabbitMQ (Testcontainers) y
deja que `CarritoEventListener` lo consuma de punta a punta, sin mockear
nada del pipeline de mensajería — incluye un caso de reentrega del mismo
evento para probar idempotencia.

El flujo real: `POST /api/carrito/checkout` en `carrito-service` publica el
evento y este servicio crea el pedido. Para probar este servicio aislado,
publicar el mensaje directo en RabbitMQ (UI en `localhost:15672`, exchange
`carrito.events`, routing key `carrito.checkout-iniciado`) — ver
`docs/events/carrito.checkout-iniciado.schema.json` para el payload.

## Estado del repo (importante)

- Repo en GitHub (`llocado/pedidos-service`), rama `main` con el código
  completo.
