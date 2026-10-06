package com.supermercado.pedidos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.application.usecase.MarcarPedidoPagadoUseCase;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pago -> pedido contra RabbitMQ y PostgreSQL reales: un pago aprobado pasa el pedido a PAGADO, uno rechazado no lo
 * toca, y los datos incoherentes van a la DLQ. Las esperas de reintento se acortan solo para que el test no tarde.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class PedidosPagosIntegrationTest {

    private static final String DLQ_APROBADO = "pedidos.pago-aprobado.dlq";

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
    private MarcarPedidoPagadoUseCase marcarPedidoPagadoUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void limpiarColas() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE_PAGO_APROBADO, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE_PAGO_RECHAZADO, false);
        rabbitAdmin.purgeQueue(DLQ_APROBADO, false);
        rabbitAdmin.purgeQueue("pedidos.pago-rechazado.dlq", false);
    }

    @Test
    void pagoAprobado_DeberiaMarcarElPedidoComoPagado_YVerseEnLaApi() throws Exception {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario, "3000");

        publicarPagoAprobado(UUID.randomUUID(), pedido.getId().getValor(), usuario, "3000.00");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.PAGADO));
        mockMvc.perform(get("/api/pedidos/{id}", pedido.getId().getValor()).with(jwtCliente(usuario)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PAGADO"));
    }

    @Test
    void pagoAprobadoEntregadoDosVeces_DeberiaDejarElPedidoPagadoSinErrores() throws Exception {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario, "3000");
        UUID eventId = UUID.randomUUID();

        publicarPagoAprobado(eventId, pedido.getId().getValor(), usuario, "3000");
        publicarPagoAprobado(eventId, pedido.getId().getValor(), usuario, "3000");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.PAGADO));
        Thread.sleep(1000);
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PAGO_APROBADO).getMessageCount()).isZero();
        assertThat(rabbitAdmin.getQueueInfo(DLQ_APROBADO).getMessageCount()).isZero();
    }

    @Test
    void pagoRechazado_NoDeberiaCambiarElPedido() throws Exception {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario, "150000");

        publicar("pago.rechazado", UUID.randomUUID(), "{\"pagoId\":\"" + UUID.randomUUID() + "\",\"pedidoId\":\"" + pedido.getId().getValor()
                + "\",\"usuarioId\":\"" + usuario + "\",\"monto\":150000,\"moneda\":\"CLP\",\"motivo\":\"MONTO_EXCEDE_LIMITE\"}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PAGO_RECHAZADO).getMessageCount()).isZero());
        Thread.sleep(500);
        assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.CREADO);
        assertThat(rabbitAdmin.getQueueInfo("pedidos.pago-rechazado.dlq").getMessageCount()).isZero();
    }

    @Test
    void pagoConMontoDistintoAlTotal_DeberiaIrALaDlqSinReintentosYDejarElPedidoPendiente() {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario, "3000");

        publicarPagoAprobado(UUID.randomUUID(), pedido.getId().getValor(), usuario, "2999");

        assertThat(rabbitTemplate.receive(DLQ_APROBADO, 10_000)).as("mensaje en la DLQ").isNotNull();
        assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.CREADO);
        verify(marcarPedidoPagadoUseCase, times(1)).execute(any(), any(), any(), any());
    }

    @Test
    void pagoDeOtroUsuario_DeberiaIrALaDlqYNoPagarElPedido() {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario, "3000");

        publicarPagoAprobado(UUID.randomUUID(), pedido.getId().getValor(), "otro-usuario-" + UUID.randomUUID(), "3000");

        assertThat(rabbitTemplate.receive(DLQ_APROBADO, 10_000)).as("mensaje en la DLQ").isNotNull();
        assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.CREADO);
    }

    @Test
    void pagoDeUnPedidoInexistente_DeberiaReintentarse1Mas3VecesYLuegoIrALaDlq() {
        publicarPagoAprobado(UUID.randomUUID(), UUID.randomUUID(), "usuario-fantasma", "3000");

        assertThat(rabbitTemplate.receive(DLQ_APROBADO, 10_000)).as("mensaje en la DLQ tras agotar reintentos").isNotNull();
        verify(marcarPedidoPagadoUseCase, times(4)).execute(any(), any(), any(), any());
    }

    @Test
    void pagoQueLlegaAntesQueElPedidoSeaVisible_DeberiaRecuperarseConLosReintentos() {
        String usuario = usuarioUnico();
        Pedido pedido = crearPedido(usuario, "3000");
        doThrow(new PedidoNoEncontradoException("todavia no visible"))
                .doThrow(new PedidoNoEncontradoException("todavia no visible"))
                .doCallRealMethod()
                .when(marcarPedidoPagadoUseCase).execute(any(), any(), any(), any());

        publicarPagoAprobado(UUID.randomUUID(), pedido.getId().getValor(), usuario, "3000");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(estadoDe(usuario)).isEqualTo(EstadoPedido.PAGADO));
        assertThat(rabbitAdmin.getQueueInfo(DLQ_APROBADO).getMessageCount()).as("no debe ir a la DLQ").isZero();
        verify(marcarPedidoPagadoUseCase, times(3)).execute(any(), any(), any(), any());
    }

    private Pedido crearPedido(String usuario, String total) {
        publicar("carrito.checkout-iniciado", UUID.randomUUID(), "{\"carritoId\":\"" + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuario
                + "\",\"total\":" + total + ",\"items\":[{\"productoId\":\"" + UUID.randomUUID()
                + "\",\"nombre\":\"Manzana\",\"precioUnitario\":" + total + ",\"moneda\":\"CLP\",\"cantidad\":1}]}",
                RabbitMQConfig.EXCHANGE_CARRITO_EVENTS);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(pedidoRepositoryPort.buscarPorUsuarioId(usuario)).hasSize(1));
        return pedidoRepositoryPort.buscarPorUsuarioId(usuario).get(0);
    }

    private EstadoPedido estadoDe(String usuario) {
        return pedidoRepositoryPort.buscarPorUsuarioId(usuario).get(0).getEstado();
    }

    private void publicarPagoAprobado(UUID eventId, UUID pedidoId, String usuario, String monto) {
        publicar("pago.aprobado", eventId, "{\"pagoId\":\"" + UUID.randomUUID() + "\",\"pedidoId\":\"" + pedidoId + "\",\"carritoId\":\""
                + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuario + "\",\"monto\":" + monto + ",\"moneda\":\"CLP\","
                + "\"items\":[{\"productoId\":\"" + UUID.randomUUID() + "\",\"cantidad\":1}]}");
    }

    private void publicar(String tipo, UUID eventId, String payload) {
        publicar(tipo, eventId, payload, RabbitMQConfig.EXCHANGE_PAGOS_EVENTS);
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

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtCliente(String usuarioId) {
        return jwt().jwt(j -> j.subject(usuarioId)).authorities(new SimpleGrantedAuthority("ROLE_cliente"));
    }
}
