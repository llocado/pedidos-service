package com.supermercado.pedidos.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.supermercado.pedidos.application.usecase.MarcarPedidoSinStockUseCase;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class StockEventListenerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID PAGO_ID = UUID.randomUUID();
    private static final UUID PEDIDO_ID = UUID.randomUUID();
    private static final String FALTANTE = "{\"productoId\":\"" + UUID.randomUUID() + "\",\"solicitado\":5,\"disponible\":2}";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private MarcarPedidoSinStockUseCase marcarPedidoSinStock;

    private StockEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new StockEventListener(marcarPedidoSinStock, objectMapper);
    }

    @Test
    void deberiaDelegarEnElCasoDeUso_CuandoElPayloadEsValido() {
        listener.escucharStockDescuentoRechazado(envelope(payload("\"u1\"", "[" + FALTANTE + "]")));

        verify(marcarPedidoSinStock).execute(EVENT_ID, PEDIDO_ID, "u1");
    }

    @Test
    void deberiaRechazar_CuandoElPayloadEstaVacioOIncompleto() {
        assertRechazado(objectMapper.readTree("{}"));
        assertRechazado(objectMapper.readTree("{\"pedidoId\":\"" + PEDIDO_ID + "\",\"usuarioId\":\"u1\",\"faltantes\":[" + FALTANTE + "]}"));
        assertRechazado(objectMapper.readTree("{\"pagoId\":\"" + PAGO_ID + "\",\"usuarioId\":\"u1\",\"faltantes\":[" + FALTANTE + "]}"));
    }

    @Test
    void deberiaRechazar_CuandoNoTraeFaltantes() {
        assertRechazado(payload("\"u1\"", "[]"));
        assertRechazado(payload("\"u1\"", "null"));
    }

    @Test
    void deberiaAceptarUsuarioIdDeExactamente100Caracteres_YRechazarUnoDe101() {
        listener.escucharStockDescuentoRechazado(envelope(payload("\"" + "u".repeat(100) + "\"", "[" + FALTANTE + "]")));
        verify(marcarPedidoSinStock).execute(any(), any(), any());
        clearInvocations(marcarPedidoSinStock);

        assertRechazado(payload("\"" + "u".repeat(101) + "\"", "[" + FALTANTE + "]"));
        assertRechazado(payload("\"\"", "[" + FALTANTE + "]"));
        assertRechazado(payload("null", "[" + FALTANTE + "]"));
    }

    @Test
    void deberiaRechazar_CuandoElEnvelopeEstaIncompleto() {
        EventEnvelope sinId = new EventEnvelope(null, "stock.descuento-rechazado", 1, Instant.now(), "app-productos", null, payload("\"u1\"", "[" + FALTANTE + "]"));
        EventEnvelope sinPayload = new EventEnvelope(EVENT_ID, "stock.descuento-rechazado", 1, Instant.now(), "app-productos", null, null);

        assertThatThrownBy(() -> listener.escucharStockDescuentoRechazado(sinId)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.escucharStockDescuentoRechazado(sinPayload)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.escucharStockDescuentoRechazado(null)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(marcarPedidoSinStock, never()).execute(any(), any(), any());
    }

    @Test
    void deberiaConvertirEnRechazoDefinitivo_UnRechazoIncoherenteConElPedido() {
        doThrow(new PagoInconsistenteException("usuario distinto")).when(marcarPedidoSinStock).execute(any(), any(), any());

        assertThatThrownBy(() -> listener.escucharStockDescuentoRechazado(envelope(payload("\"u1\"", "[" + FALTANTE + "]"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void deberiaPropagarSinEnvolver_ElPedidoNoEncontradoParaQueSeReintente() {
        doThrow(new PedidoNoEncontradoException("no visible")).when(marcarPedidoSinStock).execute(any(), any(), any());

        assertThatThrownBy(() -> listener.escucharStockDescuentoRechazado(envelope(payload("\"u1\"", "[" + FALTANTE + "]"))))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    private void assertRechazado(JsonNode payload) {
        assertThatThrownBy(() -> listener.escucharStockDescuentoRechazado(envelope(payload)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(marcarPedidoSinStock, never()).execute(any(), any(), any());
        assertThat(payload).isNotNull();
    }

    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(EVENT_ID, "stock.descuento-rechazado", 1, Instant.now(), "app-productos", null, payload);
    }

    private JsonNode payload(String usuarioId, String faltantes) {
        return objectMapper.readTree("{\"pagoId\":\"" + PAGO_ID + "\",\"pedidoId\":\"" + PEDIDO_ID + "\",\"usuarioId\":" + usuarioId
                + ",\"faltantes\":" + faltantes + "}");
    }
}
