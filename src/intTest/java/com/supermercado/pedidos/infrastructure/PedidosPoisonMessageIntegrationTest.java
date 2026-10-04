package com.supermercado.pedidos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.listener.CarritoEventListener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduce el incidente que motivo esta politica: un mensaje que fallaba en
 * el listener se reencolaba sin fin (cientos de miles de lineas de log por
 * segundo). Ahora debe terminar en la DLQ, sin bloquear la cola. Las esperas
 * entre reintentos se acortan solo para que el test no tarde.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class PedidosPoisonMessageIntegrationTest {

    private static final String DLQ = "pedidos.carrito-checkout-iniciado.dlq";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private PedidoRepositoryPort pedidoRepositoryPort;

    @MockitoSpyBean
    private CrearPedidoDesdeCarritoUseCase crearPedidoDesdeCarritoUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private ListAppender<ILoggingEvent> logs;
    private Logger loggerListener;

    @BeforeEach
    void limpiarColasYCapturarLogs() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE_CHECKOUT_INICIADO, false);
        rabbitAdmin.purgeQueue(DLQ, false);
        loggerListener = (Logger) LoggerFactory.getLogger(CarritoEventListener.class);
        logs = new ListAppender<>();
        logs.start();
        loggerListener.addAppender(logs);
    }

    @AfterEach
    void soltarLogs() {
        loggerListener.detachAppender(logs);
    }

    @Test
    void payloadInvalido_DeberiaIrALaDlqSinReintentosYSinCrearPedido() {
        String usuarioId = "usuario-poison-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publicar(envelopeJson(eventId, "{\"carritoId\":\"" + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuarioId + "\"}"));

        Message enDlq = rabbitTemplate.receive(DLQ, 10_000);
        assertThat(enDlq).as("mensaje en la DLQ").isNotNull();
        assertThat(new String(enDlq.getBody(), StandardCharsets.UTF_8)).contains(eventId.toString());
        verify(crearPedidoDesdeCarritoUseCase, never()).execute(any(), any(), any(), any());
        assertThat(pedidoRepositoryPort.buscarPorUsuarioId(usuarioId)).isEmpty();
        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("Evento descartado a la DLQ"))
                .as("se descarta una sola vez, sin reintentos")
                .hasSize(1);
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_CHECKOUT_INICIADO).getMessageCount()).isZero();
    }

    @Test
    void jsonRoto_DeberiaIrALaDlq() {
        publicar("esto{no es json");

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ").isNotNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_CHECKOUT_INICIADO).getMessageCount()).isZero();
    }

    @Test
    void falloTransitorioPersistente_DeberiaHacer1IntentoY3ReintentosYLuegoIrALaDlq() throws Exception {
        doThrow(new IllegalStateException("fallo transitorio simulado"))
                .when(crearPedidoDesdeCarritoUseCase).execute(any(), any(), any(), any());

        publicar(envelopeJson(UUID.randomUUID(), payloadValido("usuario-transitorio-" + UUID.randomUUID())));

        assertThat(rabbitTemplate.receive(DLQ, 10_000)).as("mensaje en la DLQ tras agotar reintentos").isNotNull();
        verify(crearPedidoDesdeCarritoUseCase, times(4)).execute(any(), any(), any(), any());

        Thread.sleep(1500);
        verify(crearPedidoDesdeCarritoUseCase, times(4)).execute(any(), any(), any(), any());
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE_CHECKOUT_INICIADO).getMessageCount()).isZero();
    }

    @Test
    void unMensajeVenenoso_NoDeberiaBloquearLaColaParaLosMensajesValidosSiguientes() {
        publicar(envelopeJson(UUID.randomUUID(), "{}"));
        String usuarioId = "usuario-valido-" + UUID.randomUUID();
        publicar(envelopeJson(UUID.randomUUID(), payloadValido(usuarioId)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(pedidoRepositoryPort.buscarPorUsuarioId(usuarioId)).hasSize(1));
        assertThat(rabbitTemplate.receive(DLQ, 5_000)).as("el venenoso quedo en la DLQ").isNotNull();
    }

    private static String payloadValido(String usuarioId) {
        return "{\"carritoId\":\"" + UUID.randomUUID() + "\",\"usuarioId\":\"" + usuarioId + "\",\"total\":3000,"
                + "\"items\":[{\"productoId\":\"" + UUID.randomUUID()
                + "\",\"nombre\":\"Manzana\",\"precioUnitario\":1500,\"moneda\":\"CLP\",\"cantidad\":2}]}";
    }

    private static String envelopeJson(UUID eventId, String payload) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"carrito.checkout-iniciado\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-10-03T12:00:00Z\",\"producer\":\"carrito-service\",\"payload\":" + payload + "}";
    }

    private void publicar(String cuerpo) {
        Message mensaje = MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build();
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE_CARRITO_EVENTS, RabbitMQConfig.ROUTING_KEY_CHECKOUT_INICIADO, mensaje);
    }
}
