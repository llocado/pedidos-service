package com.supermercado.pedidos.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.supermercado.pedidos.application.usecase.MarcarPedidoPagadoUseCase;
import com.supermercado.pedidos.application.usecase.RegistrarPagoRechazadoUseCase;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.math.BigDecimal;
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
class PagoEventListenerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID PAGO_ID = UUID.randomUUID();
    private static final UUID PEDIDO_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private MarcarPedidoPagadoUseCase marcarPedidoPagado;

    @Mock
    private RegistrarPagoRechazadoUseCase registrarPagoRechazado;

    private PagoEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PagoEventListener(marcarPedidoPagado, registrarPagoRechazado, objectMapper);
    }

    @Test
    void aprobado_DeberiaDelegarEnElCasoDeUso_CuandoElPayloadEsValido() {
        listener.escucharPagoAprobado(envelope(aprobado("\"u1\"", "3000", "\"CLP\"")));

        verify(marcarPedidoPagado).execute(EVENT_ID, PEDIDO_ID, "u1", new BigDecimal("3000"), "CLP");
    }

    @Test
    void aprobado_DeberiaRechazar_CuandoFaltanCampos() {
        assertAprobadoRechazado(objectMapper.readTree("{\"pedidoId\":\"" + PEDIDO_ID + "\",\"usuarioId\":\"u1\",\"monto\":3000,\"moneda\":\"CLP\"}"));
        assertAprobadoRechazado(objectMapper.readTree("{\"pagoId\":\"" + PAGO_ID + "\",\"usuarioId\":\"u1\",\"monto\":3000,\"moneda\":\"CLP\"}"));
        assertAprobadoRechazado(objectMapper.readTree("{}"));
    }

    @Test
    void aprobado_DeberiaAceptarUsuarioIdDeExactamente100Caracteres_YRechazarUnoDe101() {
        listener.escucharPagoAprobado(envelope(aprobado("\"" + "u".repeat(100) + "\"", "3000", "\"CLP\"")));
        verify(marcarPedidoPagado).execute(any(), any(), any(), any(), any());
        clearInvocations(marcarPedidoPagado);

        assertAprobadoRechazado(aprobado("\"" + "u".repeat(101) + "\"", "3000", "\"CLP\""));
        assertAprobadoRechazado(aprobado("\" \"", "3000", "\"CLP\""));
        assertAprobadoRechazado(aprobado("null", "3000", "\"CLP\""));
    }

    @Test
    void aprobado_DeberiaRechazar_CuandoElMontoOLaMonedaSonInvalidos() {
        assertAprobadoRechazado(aprobado("\"u1\"", "0", "\"CLP\""));
        assertAprobadoRechazado(aprobado("\"u1\"", "-5", "\"CLP\""));
        assertAprobadoRechazado(aprobado("\"u1\"", "null", "\"CLP\""));
        assertAprobadoRechazado(aprobado("\"u1\"", "3000", "\"clp\""));
        assertAprobadoRechazado(aprobado("\"u1\"", "3000", "\"CL\""));
        assertAprobadoRechazado(aprobado("\"u1\"", "3000", "null"));
    }

    @Test
    void aprobado_DeberiaRechazar_CuandoUnCampoTieneElTipoIncorrecto() {
        assertAprobadoRechazado(objectMapper.readTree("{\"pagoId\":\"no-es-uuid\",\"pedidoId\":\"" + PEDIDO_ID + "\",\"usuarioId\":\"u1\",\"monto\":1,\"moneda\":\"CLP\"}"));
    }

    @Test
    void aprobado_DeberiaRechazarSinReencolar_CuandoElPagoEsIncoherenteConElPedido() {
        doThrow(new PagoInconsistenteException("usuario distinto")).when(marcarPedidoPagado).execute(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharPagoAprobado(envelope(aprobado("\"u1\"", "3000", "\"CLP\""))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void aprobado_DeberiaPropagarElErrorTalCualParaQueSeReintente_CuandoElPedidoTodaviaNoExiste() {
        PedidoNoEncontradoException noVisible = new PedidoNoEncontradoException("Pedido no encontrado: " + PEDIDO_ID);
        doThrow(noVisible).when(marcarPedidoPagado).execute(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharPagoAprobado(envelope(aprobado("\"u1\"", "3000", "\"CLP\""))))
                .isSameAs(noVisible);
    }

    @Test
    void aprobado_DeberiaPropagarElErrorTalCual_CuandoElFalloEsTransitorio() {
        IllegalStateException transitorio = new IllegalStateException("base de datos no disponible");
        doThrow(transitorio).when(marcarPedidoPagado).execute(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharPagoAprobado(envelope(aprobado("\"u1\"", "3000", "\"CLP\""))))
                .isSameAs(transitorio);
    }

    @Test
    void ambos_DeberianRechazar_CuandoElEnvelopeEstaIncompleto() {
        EventEnvelope sinId = new EventEnvelope(null, "pago.aprobado", 1, Instant.now(), "pagos-service", null, aprobado("\"u1\"", "3000", "\"CLP\""));
        EventEnvelope sinPayload = new EventEnvelope(EVENT_ID, "pago.aprobado", 1, Instant.now(), "pagos-service", null, null);

        for (EventEnvelope malo : new EventEnvelope[] {sinId, sinPayload, null}) {
            assertThatThrownBy(() -> listener.escucharPagoAprobado(malo)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
            assertThatThrownBy(() -> listener.escucharPagoRechazado(malo)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        verify(marcarPedidoPagado, never()).execute(any(), any(), any(), any(), any());
        verify(registrarPagoRechazado, never()).execute(any(), any(), any());
    }

    @Test
    void rechazado_DeberiaDelegarEnElCasoDeUso_CuandoElPayloadEsValido() {
        listener.escucharPagoRechazado(envelope(rechazado("\"MONTO_EXCEDE_LIMITE\"")));

        verify(registrarPagoRechazado).execute(EVENT_ID, PEDIDO_ID, "MONTO_EXCEDE_LIMITE");
    }

    @Test
    void rechazado_DeberiaAceptarUnMotivoDeExactamente50Caracteres_YRechazarUnoDe51() {
        listener.escucharPagoRechazado(envelope(rechazado("\"" + "m".repeat(50) + "\"")));
        verify(registrarPagoRechazado).execute(any(), any(), any());
        clearInvocations(registrarPagoRechazado);

        assertRechazadoRechazado(rechazado("\"" + "m".repeat(51) + "\""));
    }

    @Test
    void rechazado_DeberiaRechazar_CuandoFaltaElPedidoOElMotivo() {
        assertRechazadoRechazado(rechazado("null"));
        assertRechazadoRechazado(rechazado("\" \""));
        assertRechazadoRechazado(objectMapper.readTree("{\"pagoId\":\"" + PAGO_ID + "\",\"motivo\":\"X\"}"));
    }

    @Test
    void deberiaLimpiarElMdc_AlTerminar() {
        listener.escucharPagoAprobado(envelope(aprobado("\"u1\"", "3000", "\"CLP\"")));
        listener.escucharPagoRechazado(envelope(rechazado("\"MONTO_EXCEDE_LIMITE\"")));

        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    private void assertAprobadoRechazado(JsonNode payload) {
        assertThatThrownBy(() -> listener.escucharPagoAprobado(envelope(payload))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(marcarPedidoPagado, never()).execute(any(), any(), any(), any(), any());
    }

    private void assertRechazadoRechazado(JsonNode payload) {
        assertThatThrownBy(() -> listener.escucharPagoRechazado(envelope(payload))).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(registrarPagoRechazado, never()).execute(any(), any(), any());
    }

    private JsonNode aprobado(String usuarioId, String monto, String moneda) {
        return objectMapper.readTree("{\"pagoId\":\"" + PAGO_ID + "\",\"pedidoId\":\"" + PEDIDO_ID + "\",\"usuarioId\":" + usuarioId
                + ",\"monto\":" + monto + ",\"moneda\":" + moneda + "}");
    }

    private JsonNode rechazado(String motivo) {
        return objectMapper.readTree("{\"pagoId\":\"" + PAGO_ID + "\",\"pedidoId\":" + (motivo.equals("null") ? "null" : "\"" + PEDIDO_ID + "\"")
                + ",\"usuarioId\":\"u1\",\"monto\":3000,\"moneda\":\"CLP\",\"motivo\":" + (motivo.equals("null") ? "\"MONTO_EXCEDE_LIMITE\"" : motivo) + "}");
    }

    private EventEnvelope envelope(JsonNode payload) {
        return new EventEnvelope(EVENT_ID, "pago.aprobado", 1, Instant.now(), "pagos-service", null, payload);
    }
}
