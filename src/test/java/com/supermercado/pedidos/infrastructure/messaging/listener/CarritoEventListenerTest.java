package com.supermercado.pedidos.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.dao.DataIntegrityViolationException;
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

        verify(crearPedido).execute(any(), any(), any(), any(), any());
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
    void deberiaRechazarSinReencolar_CuandoLaMonedaNoSonTresLetrasMayusculas() {
        for (String moneda : new String[] {"clp", "CL", "CLPX", "ñ12", "12$", ""}) {
            String item = ITEM_VALIDO.replace("\"moneda\":\"CLP\"", "\"moneda\":\"" + moneda + "\"");
            assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));
        }
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoLaCantidadSuperaElTope() {
        String item = ITEM_VALIDO.replace("\"cantidad\":2", "\"cantidad\":" + (CarritoEventListener.MAX_CANTIDAD + 1));
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));
    }

    @Test
    void deberiaAceptar_CuandoLaCantidadEsElTopeExacto() {
        String item = ITEM_VALIDO.replace("\"cantidad\":2", "\"cantidad\":" + CarritoEventListener.MAX_CANTIDAD);
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));

        verify(crearPedido).execute(any(), any(), any(), any(), any());
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElPrecioSuperaLaColumna() {
        String item = ITEM_VALIDO.replace("\"precioUnitario\":1500", "\"precioUnitario\":10000000000.00");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElPrecioTieneMasDeDosDecimales() {
        String item = ITEM_VALIDO.replace("\"precioUnitario\":1500", "\"precioUnitario\":0.001");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));
    }

    @Test
    void deberiaAceptar_CuandoElPrecioTieneDosDecimalesOCeroDecimalesDeRelleno() {
        for (String precio : new String[] {"1500.50", "1500.500", "1500.00", "9999999999.99"}) {
            String item = ITEM_VALIDO.replace("\"precioUnitario\":1500", "\"precioUnitario\":" + precio).replace("\"cantidad\":2", "\"cantidad\":1");
            listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + item + "]")));
        }
        verify(crearPedido, times(4)).execute(any(), any(), any(), any(), any());
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoLaSumaDelTotalSuperaLaColumna() {
        String caro = ITEM_VALIDO.replace("\"precioUnitario\":1500", "\"precioUnitario\":9999999999.99").replace("\"cantidad\":2", "\"cantidad\":1");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + caro + "," + caro + "]")));
    }

    @Test
    void deberiaAceptarUsuarioIdDe100Caracteres_YRechazarUnoDe101() {
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"" + "u".repeat(100) + "\"", "[" + ITEM_VALIDO + "]")));
        verify(crearPedido).execute(any(), any(), any(), any(), any());
        clearInvocations(crearPedido);

        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"" + "u".repeat(101) + "\"", "[" + ITEM_VALIDO + "]")));
    }

    @Test
    void deberiaAceptarNombreDe150Caracteres_YRechazarUnoDe151() {
        String nombre150 = ITEM_VALIDO.replace("\"nombre\":\"Manzana\"", "\"nombre\":\"" + "n".repeat(CarritoEventListener.MAX_NOMBRE) + "\"");
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + nombre150 + "]")));
        verify(crearPedido).execute(any(), any(), any(), any(), any());
        clearInvocations(crearPedido);

        String nombre151 = ITEM_VALIDO.replace("\"nombre\":\"Manzana\"", "\"nombre\":\"" + "n".repeat(CarritoEventListener.MAX_NOMBRE + 1) + "\"");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + nombre151 + "]")));
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoLosItemsTienenMonedasDistintas() {
        String usd = ITEM_VALIDO.replace("\"moneda\":\"CLP\"", "\"moneda\":\"USD\"");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "," + usd + "]")));
    }

    @Test
    void deberiaAceptarVariosItemsDeLaMismaMoneda() {
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "," + ITEM_VALIDO + "]")));
        verify(crearPedido).execute(any(), any(), any(), any(), any());
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElTotalEsCero() {
        String gratis = ITEM_VALIDO.replace("\"precioUnitario\":1500", "\"precioUnitario\":0");
        assertRechazado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + gratis + "]")));
    }

    @Test
    void deberiaAceptarUnItemGratisSiElTotalDelPedidoEsPositivo() {
        String gratis = ITEM_VALIDO.replace("\"precioUnitario\":1500", "\"precioUnitario\":0");
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + gratis + "," + ITEM_VALIDO + "]")));
        verify(crearPedido).execute(any(), any(), any(), any(), any());
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
        verify(crearPedido, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void deberiaRechazarSinReencolar_CuandoElCasoDeUsoViolaUnaReglaDeDominio() {
        doThrow(new PedidoSinItemsException("sin items")).when(crearPedido).execute(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(
                envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void deberiaPropagarElErrorTalCual_CuandoElFalloEsTransitorio_ParaQueSpringReintente() {
        IllegalStateException transitorio = new IllegalStateException("base de datos no disponible");
        doThrow(transitorio).when(crearPedido).execute(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(
                envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]"))))
                .isSameAs(transitorio);
    }

    @Test
    void deberiaDescartarSinError_CuandoUnDuplicadoConcurrenteChocaConLaUnicaYElEventoYaEstaProcesado() {
        doThrow(new DataIntegrityViolationException("pk_eventos_procesados")).when(crearPedido).execute(any(), any(), any(), any(), any());
        when(crearPedido.yaProcesado(EVENT_ID)).thenReturn(true);

        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]")));

        verify(crearPedido).yaProcesado(EVENT_ID);
    }

    @Test
    void deberiaPropagarElError_CuandoLaViolacionDeIntegridadNoEsUnDuplicado() {
        DataIntegrityViolationException integridad = new DataIntegrityViolationException("otra constraint");
        doThrow(integridad).when(crearPedido).execute(any(), any(), any(), any(), any());
        when(crearPedido.yaProcesado(EVENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(
                envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]"))))
                .isSameAs(integridad);
    }

    @Test
    void deberiaLimpiarElMdc_AlTerminar() {
        listener.escucharCheckoutIniciado(envelope(payload("\"" + UUID.randomUUID() + "\"", "\"u1\"", "[" + ITEM_VALIDO + "]")));

        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    private void assertRechazado(EventEnvelope envelope) {
        assertThatThrownBy(() -> listener.escucharCheckoutIniciado(envelope))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(crearPedido, never()).execute(any(), any(), any(), any(), any());
    }

    private tools.jackson.databind.JsonNode payload(String carritoId, String usuarioId, String items) {
        return objectMapper.readTree(
                "{\"carritoId\":" + carritoId + ",\"usuarioId\":" + usuarioId + ",\"items\":" + items + ",\"total\":3000}");
    }

    private EventEnvelope envelope(tools.jackson.databind.JsonNode payload) {
        return new EventEnvelope(EVENT_ID, "carrito.checkout-iniciado", 1, Instant.now(), "carrito-service", null, payload);
    }
}
