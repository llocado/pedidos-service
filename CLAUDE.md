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
  (`EventoProcesadoPort`), compartida por los tres listeners (checkout,
  `pago.aprobado`, `pago.rechazado`).
- `pago.aprobado` pasa el pedido a PAGADO (`MarcarPedidoPagadoUseCase`) tras
  validar usuario, monto y moneda (la del pedido es la de sus ítems); pedido inexistente => se REINTENTA (carrera con la
  publicación previa al commit de `pedido.creado`), datos incoherentes => DLQ.
  `pago.rechazado` solo se registra. El `correlationId` del checkout se propaga.
- `stock.descuento-rechazado` (de `app-productos`, cuando un pago aprobado no pudo descontar el stock) pasa el pedido de
  PAGADO (o CREADO) a **`SIN_STOCK`** (`MarcarPedidoSinStockUseCase`), estado terminal para gestión manual. `SIN_STOCK`
  es una rama lateral: se llega desde PAGADO o CREADO y no se sale (`EstadoPedido.puedeTransicionarA` lo trata aparte,
  porque no está en el orden lineal). Como `pago.aprobado` llega en paralelo a pedidos y a productos, el rechazo puede
  adelantarse: no se reintenta esperando el pago (con pedidos caído o con retraso se perdía en la DLQ), el rechazo ya
  prueba que el pago se aprobó, así que CREADO pasa directo a SIN_STOCK y el `pago.aprobado` tardío se ignora.
  Pedido inexistente => se REINTENTA (1 + 3); usuario distinto => DLQ.
- **Bloqueo optimista en `PedidoEntity` (`@Version`, columna `pedidos.version`).** `pago.aprobado` y
  `stock.descuento-rechazado` se procesan en paralelo (colas distintas, hilos distintos) y ambos leen, cambian y guardan
  el estado: sin versión, el último en escribir pisaba al otro (un pedido sin stock podía quedar PAGADO). El segundo en
  confirmar falla con `OptimisticLockingFailureException`, el listener reintenta (1 + 3) y ve el estado nuevo. Todo caso
  de uso que escriba el pedido debe ser `@Transactional`. Probado con `PedidoConcurrenciaIntegrationTest`.
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
./gradlew jacocoFullReport   # cobertura test + intTest -> build/reports/jacoco/jacocoFullReport/index.html
./gradlew pitest              # tests de mutacion (solo unitarios) -> build/reports/pitest/index.html
./gradlew sonar              # analisis SonarQube (ver ../CLAUDE.md: requiere SONAR_TOKEN y el perfil `calidad`)
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
