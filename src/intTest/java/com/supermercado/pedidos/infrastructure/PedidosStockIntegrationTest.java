package com.supermercado.pedidos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.application.usecase.MarcarPedidoPagadoUseCase;
import com.supermercado.pedidos.application.usecase.MarcarPedidoSinStockUseCase;
import com.supermercado.pedidos.domain.model.EstadoPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * stock.descuento-rechazado -> pedido contra RabbitMQ y PostgreSQL reales: un pedido PAGADO pasa a SIN_STOCK, el que
 * todavia esta en CREADO se reintenta hasta que el pago llegue, y los datos incoherentes van a la DLQ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class PedidosStockIntegrationTest {

    private static final String DLQ = "pedidos.stock-descuento-rechazado.dlq";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private PedidoRepositoryPort pedidoRepositoryPort;

    @MockitoSpyBean
    private MarcarPedidoSinStockUseCase marcarPedidoSinStockUseCase;

    @MockitoSpyBean
    private MarcarPedidoPagadoUseCase marcarPedidoPagadoUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void limpiarColas() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE_STOCK_DESCUENTO_RECHAZADO, false);
        rabbitAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void stockRechazado_DeberiaPasarElPedidoPagadoASinStock_YVerseEnLaApi() throws Exception {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedidoPagado(usuario);

        publicarStockRechazado(UUID.randomUUID(), pedido.getId().getValor(), usuario);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.SIN_STOCK));
        mockMvc.perform(get("/api/pedidos/{id}", pedido.getId().getValor()).with(jwtCliente(usuario)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SIN_STOCK"));
    }

    @Test
    void stockRechazadoEntregadoDosVeces_DeberiaDejarElPedidoSinStockSinErrores() throws Exception {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedidoPagado(usuario);
        UUID eventId = UUID.randomUUID();

        publicarStockRechazado(eventId, pedido.getId().getValor(), usuario);
        publicarStockRechazado(eventId, pedido.getId().getValor(), usuario);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.SIN_STOCK));
        Thread.sleep(1000);
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_STOCK_DESCUENTO_RECHAZADO).getMessageCount()).isZero();
        assertThat(rabbitAdmin.getQueueInfo(DLQ).getMessageCount()).isZero();
    }

    @Test
    void stockRechazadoQueLlegaAntesQueElPago_DeberiaPasarElPedidoASinStock_YElPagoTardioSeIgnora() {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario); // CREADO: pago.aprobado todavia no llega (pedidos caido, backlog, etc.)

        publicarStockRechazado(UUID.randomUUID(), pedido.getId().getValor(), usuario);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.SIN_STOCK));

        // El pago.aprobado llega despues, mucho mas tarde que cualquier reintento: se procesa y no cambia nada.
        publicarPagoAprobado(pedido.getId().getValor(), usuario);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(marcarPedidoPagadoUseCase).execute(any(), eq(pedido.getId().getValor()), eq(usuario), any(), any()));
        assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.SIN_STOCK);
        verify(marcarPedidoSinStockUseCase, times(1)).execute(any(), any(), any());
        assertThat(rabbitAdmin.getQueueInfo(DLQ).getMessageCount()).as("sin DLQ").isZero();
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PAGO_APROBADO + ".dlq").getMessageCount()).isZero();
    }

    @Test
    void stockRechazadoDeUnPedidoInexistente_DeberiaReintentarse1Mas3VecesYLuegoIrALaDlq() {
        publicarStockRechazado(UUID.randomUUID(), UUID.randomUUID(), usuarioUnico());

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ tras agotar reintentos").isNotNull();
        verify(marcarPedidoSinStockUseCase, times(4)).execute(any(), any(), any());
    }

    @Test
    void stockRechazadoDeOtroUsuario_DeberiaIrALaDlqSinReintentosYNoTocarElPedido() {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedidoPagado(usuario);

        publicarStockRechazado(UUID.randomUUID(), pedido.getId().getValor(), "otro-usuario-" + UUID.randomUUID());

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ").isNotNull();
        assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.PAGADO);
        verify(marcarPedidoSinStockUseCase, times(1)).execute(any(), any(), any());
    }

    @Test
    void payloadInvalido_DeberiaIrALaDlqSinReintentos() {
        publicar("stock.descuento-rechazado", UUID.randomUUID(), "{\"pedidoId\":\"" + UUID.randomUUID() + "\"}", RabbitMQConfig.EXCHANGE_PRODUCTOS_EVENTS);

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ").isNotNull();
        verify(marcarPedidoSinStockUseCase, times(0)).execute(any(), any(), any());
    }

    // ---------------------------------------------------------------- helpers

    private Pedido crearPedido(String usuario) {
        publicar("carrito.checkout-iniciado", UUID.randomUUID(), "{\"carritoId\":\"" + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuario
                + "\",\"total\":3000,\"items\":[{\"productoId\":\"" + UUID.randomUUID()
                + "\",\"nombre\":\"Manzana\",\"precioUnitario\":3000,\"moneda\":\"CLP\",\"cantidad\":1}]}",
                RabbitMQConfig.EXCHANGE_CARRITO_EVENTS);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pedidoRepositoryPort.buscarPorUsuarioId(usuario)).hasSize(1));
        return pedidoRepositoryPort.buscarPorUsuarioId(usuario).get(0);
    }

    private void publicarPagoAprobado(UUID pedidoId, String usuario) {
        publicar("pago.aprobado", UUID.randomUUID(), "{\"pagoId\":\"" + UUID.randomUUID() + "\",\"pedidoId\":\"" + pedidoId
                + "\",\"carritoId\":\"" + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuario + "\",\"monto\":3000,\"moneda\":\"CLP\","
                + "\"items\":[{\"productoId\":\"" + UUID.randomUUID() + "\",\"cantidad\":1}]}", RabbitMQConfig.EXCHANGE_PAGOS_EVENTS);
    }

    private Pedido crearPedidoPagado(String usuario) {
        Pedido pedido = crearPedido(usuario);
        publicarPagoAprobado(pedido.getId().getValor(), usuario);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.PAGADO));
        return pedido;
    }

    private EstadoPedido estadoDe(String usuario) {
        return pedidoRepositoryPort.buscarPorUsuarioId(usuario).get(0).getEstado();
    }

    private void publicarStockRechazado(UUID eventId, UUID pedidoId, String usuario) {
        publicar("stock.descuento-rechazado", eventId, "{\"pagoId\":\"" + UUID.randomUUID() + "\",\"pedidoId\":\"" + pedidoId
                + "\",\"usuarioId\":\"" + usuario + "\",\"faltantes\":[{\"productoId\":\"" + UUID.randomUUID()
                + "\",\"solicitado\":5,\"disponible\":2}]}", RabbitMQConfig.EXCHANGE_PRODUCTOS_EVENTS);
    }

    private void publicar(String tipo, UUID eventId, String payload, String exchange) {
        String cuerpo = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + tipo + "\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-10-04T12:00:00Z\",\"producer\":\"pruebas\",\"payload\":" + payload + "}";
        Message mensaje = MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8)).setContentType("application/json").build();
        rabbitTemplate.send(exchange, tipo, mensaje);
    }

    private static String usuarioUnico() {
        return "usuario-integ-" + UUID.randomUUID();
    }

    private static JwtRequestPostProcessor jwtCliente(String usuarioId) {
        return jwt().jwt(j -> j.subject(usuarioId)).authorities(new SimpleGrantedAuthority("ROLE_cliente"));
    }
}
