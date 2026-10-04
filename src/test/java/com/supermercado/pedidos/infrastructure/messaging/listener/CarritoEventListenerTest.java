package com.supermercado.pedidos.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase;
import com.supermercado.pedidos.domain.exception.PedidoSinItemsException;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CarritoEventListenerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String ITEM_VALIDO =
            "{\"productoId\":\"" + UUID.randomUUID() + "\",\"nombre\":\"Manzana\",\"precioUnitario\":1500,\"moneda\":\"CLP\",\"cantidad\":2}";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private CrearPedidoDesdeCarritoUseCase crearPedido;

    private CarritoEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new CarritoEventListener(crearPedido, objectMapper);
    }

    @Test
    void deberiaDelegarEnElCasoDeUso_CuandoElPayloadEsValido() {
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]")));

        verify(crearPedido).execute(any(), any(), any(), any());
    }

    @Test
    void deberiaRechazarSinReencolar_YNoLlamarAlCasoDeUso_CuandoElPayloadEstaVacio() {
        assertRechazado(envelope(objectMapper.readTree("{}")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoFaltaElCarritoId() {
        assertRechazado(envelope(payload("null", "\"u1\"", "[" + ITEM_VALIDO + "]")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoNoHayItems() {
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[]")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoLaCantidadEsInvalida() {
        String item = ITEM_VALIDO.replace("\"cantidad\":2", "\"cantidad\":0");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElUsuarioIdEsDemasiadoLargo() {
        String largo = "\"" + "u".repeat(300) + "\"";
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", largo, "[" + ITEM_VALIDO + "]")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoHayDemasiadosItems() {
        String items = "[" + String.join(",", java.util.Collections.nCopies(CarritoEventListener.MAX_ITEMS + 1, ITEM_VALIDO)) + "]";
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", items)));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoUnCampoTieneElTipoIncorrecto() {
        assertRechazado(envelope(objectMapper.readTree("{\"carritoId\":\"no-es-uuid\",\"usuarioId\":\"u1\",\"items\":[]}")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElEnvelopeNoTieneEventId() {
        EventEnvelope sinId = new EventEnvelope(null, "carrito.checkout-iniciado", 1, Instant.now(), "carrito-service", null,
                payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]"));

        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(sinId))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(crearPedido, never()).execute(any(), any(), any(), any());
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElCasoDeUsoViolaUnaReglaDeDominio() {
        doThrow(new PedidoSinItemsException("sin items")).when(crearPedido).execute(any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(
                envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void deberiaPropagarElErrorTalCual_CuandoElFalloEsTransitorio_ParaQueSpringReintente() {
        IllegalStateException transitorio = new IllegalStateException("base de datos no disponible");
        doThrow(transitorio).when(crearPedido).execute(any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(
                envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]"))))
                .isSameAs(transitorio);
    }

    @Test
    void deberiaLimpiarElMdc_AlTerminar() {
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]")));

        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    private void assertRechazado(EventEnvelope envelope) {
        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(envelope))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(crearPedido, never()).execute(any(), any(), any(), any());
    }

    private tools.jackson.databind.JsonNode payload(String carritoId, String usuarioId, String items) {
        return objectMapper.readTree(
                "{\"carritoId\":" + carritoId + ",\"usuarioId\":" + usuarioId + ",\"items\":" + items + ",\"total\":3000}");
    }

    private EventEnvelope envelope(tools.jackson.databind.JsonNode payload) {
        return new EventEnvelope(EVENT_ID, "carrito.checkout-iniciado", 1, Instant.now(), "carrito-service", null, payload);
    }
}
