package com.supermercado.pedidos.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload.ItemEventoDto;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Suite de integracion end-to-end: publica un evento carrito.checkout-iniciado
 * real en un RabbitMQ de Testcontainers, deja que CarritoEventListener lo
 * consuma de verdad (sin mockear nada del pipeline de mensajeria), y verifica
 * que el pedido queda persistido en un PostgreSQL real y es consultable via
 * REST. Solo se mockea JwtDecoder, para no depender de un Keycloak real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PedidosIntegrationTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private PedidoRepositoryPort pedidoRepositoryPort;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void checkoutIniciado_DeberiaCrearElPedidoYQuedarConsultableViaRest() {
        String usuarioId = usuarioDePrueba();
        UUID carritoId = UUID.randomUUID();
        UUID productoId = UUID.randomUUID();

        publicarCheckoutIniciado(UUID.randomUUID(), carritoId, usuarioId, productoId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(pedidoRepositoryPort.buscarPorUsuarioId(usuarioId)).hasSize(1));

        var pedido = pedidoRepositoryPort.buscarPorUsuarioId(usuarioId).get(0);

        assertThatPuedeConsultarsePorRest(usuarioId, pedido.getId().getValor(), carritoId, productoId);
    }

    @Test
    void checkoutIniciado_NoDeberiaDuplicarElPedido_CuandoElMismoEventoSeReentrega() {
        String usuarioId = usuarioDePrueba();
        UUID eventId = UUID.randomUUID();
        UUID carritoId = UUID.randomUUID();
        UUID productoId = UUID.randomUUID();

        publicarCheckoutIniciado(eventId, carritoId, usuarioId, productoId);
        publicarCheckoutIniciado(eventId, carritoId, usuarioId, productoId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(pedidoRepositoryPort.buscarPorUsuarioId(usuarioId)).hasSize(1));
    }

    @Test
    void obtenerPedido_DeberiaRetornar401_CuandoNoHayToken() throws Exception {
        mockMvc.perform(get("/api/pedidos/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void obtenerPedido_DeberiaRetornar404_CuandoNoExiste() throws Exception {
        mockMvc.perform(get("/api/pedidos/{id}", UUID.randomUUID()).with(jwtCliente(usuarioDePrueba())))
                .andExpect(status().isNotFound());
    }

    private void assertThatPuedeConsultarsePorRest(String usuarioId, UUID pedidoId, UUID carritoId, UUID productoId) {
        try {
            mockMvc.perform(get("/api/pedidos/{id}", pedidoId).with(jwtCliente(usuarioId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.carritoIdOrigen").value(carritoId.toString()))
                    .andExpect(jsonPath("$.estado").value("CREADO"))
                    .andExpect(jsonPath("$.total").value(3000))
                    .andExpect(jsonPath("$.items[0].productoId").value(productoId.toString()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String usuarioDePrueba() {
        return "usuario-integ-" + UUID.randomUUID();
    }

    private void publicarCheckoutIniciado(UUID eventId, UUID carritoId, String usuarioId, UUID productoId) {
        CarritoCheckoutIniciadoPayload payload = new CarritoCheckoutIniciadoPayload(
                carritoId,
                usuarioId,
                List.of(new ItemEventoDto(productoId, "Manzana Fuji", new BigDecimal("1500"), "CLP", 2)),
                new BigDecimal("3000")
        );

        EventEnvelope envelope = new EventEnvelope(
                eventId,
                RabbitMQConfig.ROUTING_KEY_CHECKOUT_INICIADO,
                1,
                Instant.now(),
                "carrito-service",
                null,
                objectMapper.valueToTree(payload)
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_CARRITO_EVENTS, RabbitMQConfig.ROUTING_KEY_CHECKOUT_INICIADO, envelope);
    }

    private static JwtRequestPostProcessor jwtCliente(String usuarioId) {
        return jwt().jwt(j -> j.subject(usuarioId))
                .authorities(new SimpleGrantedAuthority("ROLE_cliente"));
    }
}
