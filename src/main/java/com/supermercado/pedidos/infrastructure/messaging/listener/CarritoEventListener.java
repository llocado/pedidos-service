package com.supermercado.pedidos.infrastructure.messaging.listener;

import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase.ItemCarritoComando;
import com.supermercado.pedidos.domain.exception.PedidoSinItemsException;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload.ItemEventoDto;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Politica de errores (ver spring.rabbitmq.listener.simple en application.yml):
 * - Mensaje irrecuperable (payload invalido, regla de dominio violada): se
 *   rechaza de inmediato a la DLQ, sin reintentos (reintentar no lo arregla).
 * - Fallo transitorio (por ejemplo, la base de datos no responde): se deja
 *   propagar; Spring reintenta hasta 3 veces con espera creciente y luego lo
 *   manda a la DLQ. Nunca se reencola en bucle.
 * Los logs llevan eventId y correlationId, pero nunca el payload completo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CarritoEventListener {

    static final int MAX_ITEMS = 500;
    /** Largos de las columnas (pedidos.usuario_id varchar(100), items_pedido.nombre varchar(150)): un texto mas largo no cabe. */
    static final int MAX_USUARIO_ID = 100;
    static final int MAX_NOMBRE = 150;
    static final int MAX_CANTIDAD = 10_000;
    static final int MAX_ESCALA = 2;
    /** Lo que cabe en numeric(12,2): un importe mayor desbordaria la columna y el mensaje se reintentaria en vano. */
    static final BigDecimal MAX_IMPORTE = new BigDecimal("9999999999.99");
    private static final Pattern MONEDA = Pattern.compile("^[A-Z]{3}$");

    private final CrearPedidoDesdeCarritoUseCase crearPedidoDesdeCarritoUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHECKOUT_INICIADO)
    public void escucharCheckoutIniciado(EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.payload() == null) {
            log.warn("Evento descartado a la DLQ: falta eventId o payload");
            throw new AmqpRejectAndDontRequeueException("Envelope invalido: falta eventId o payload");
        }

        UUID correlacion = envelope.correlationId() != null ? envelope.correlationId() : envelope.eventId();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlacion.toString())) {
            log.info("Evento recibido eventType={} eventId={}", envelope.eventType(), envelope.eventId());

            CarritoCheckoutIniciadoPayload payload = leerPayload(envelope);
            String motivo = validar(payload);
            if (motivo != null) {
                log.warn("Evento descartado a la DLQ eventId={}: payload invalido ({})", envelope.eventId(), motivo);
                throw new AmqpRejectAndDontRequeueException("Payload invalido: " + motivo);
            }

            List<ItemCarritoComando> items = payload.items().stream()
                    .map(item -> new ItemCarritoComando(
                            item.productoId(), item.nombre(), item.precioUnitario(), item.moneda(), item.cantidad()))
                    .toList();

            try {
                crearPedidoDesdeCarritoUseCase.execute(envelope.eventId(), correlacion, payload.carritoId(), payload.usuarioId(), items);
            } catch (IllegalArgumentException | PedidoSinItemsException e) {
                log.warn("Evento descartado a la DLQ eventId={}: regla de dominio violada ({})", envelope.eventId(), e.getMessage());
                throw new AmqpRejectAndDontRequeueException("Regla de dominio violada: " + e.getMessage(), e);
            } catch (DataIntegrityViolationException e) {
                if (crearPedidoDesdeCarritoUseCase.yaProcesado(envelope.eventId())) {
                    log.info("Duplicado concurrente descartado eventId={}", envelope.eventId());
                    return;
                }
                log.warn("Fallo de integridad al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
                throw e;
            } catch (RuntimeException e) {
                log.warn("Fallo al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
                throw e;
            }
        }
    }

    private CarritoCheckoutIniciadoPayload leerPayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.payload(), CarritoCheckoutIniciadoPayload.class);
        } catch (JacksonException e) {
            log.warn("Evento descartado a la DLQ eventId={}: payload no deserializable", envelope.eventId());
            throw new AmqpRejectAndDontRequeueException("Payload no deserializable", e);
        }
    }

    /** Retorna el motivo si el payload es invalido, o null si esta bien. */
    static String validar(CarritoCheckoutIniciadoPayload payload) {
        if (payload == null) return "payload nulo";
        String motivo = validarCabecera(payload);
        return motivo != null ? motivo : validarItems(payload.items());
    }

    private static String validarCabecera(CarritoCheckoutIniciadoPayload payload) {
        if (payload.carritoId() == null) return "falta carritoId";
        if (textoInvalido(payload.usuarioId(), MAX_USUARIO_ID)) return "usuarioId ausente o demasiado largo";
        return null;
    }

    private static String validarItems(List<ItemEventoDto> items) {
        if (items == null || items.isEmpty()) return "sin items";
        if (items.size() > MAX_ITEMS) return "demasiados items";
        BigDecimal total = BigDecimal.ZERO;
        for (ItemEventoDto item : items) {
            String motivo = validarItem(item);
            if (motivo != null) return motivo;
            total = total.add(item.precioUnitario().multiply(BigDecimal.valueOf(item.cantidad())));
        }
        // pagos-service rechaza lo mismo (monedas mezcladas, total 0): si aqui pasara, el pedido quedaria en CREADO para siempre.
        if (items.stream().map(ItemEventoDto::moneda).distinct().count() > 1) return "items con monedas distintas";
        if (total.signum() <= 0) return "total debe ser mayor a cero";
        return total.compareTo(MAX_IMPORTE) > 0 ? "total demasiado grande" : null;
    }

    private static String validarItem(ItemEventoDto item) {
        if (item == null) return "item nulo";
        if (item.productoId() == null) return "item sin productoId";
        if (textoInvalido(item.nombre(), MAX_NOMBRE)) return "item con nombre invalido";
        if (precioInvalido(item.precioUnitario())) return "item con precio invalido";
        if (item.moneda() == null || !MONEDA.matcher(item.moneda()).matches()) return "item con moneda invalida";
        if (item.cantidad() <= 0 || item.cantidad() > MAX_CANTIDAD) return "item con cantidad invalida";
        return null;
    }

    private static boolean precioInvalido(BigDecimal precio) {
        return precio == null || precio.signum() < 0 || precio.compareTo(MAX_IMPORTE) > 0
                || precio.stripTrailingZeros().scale() > MAX_ESCALA;
    }

    private static boolean textoInvalido(String texto, int maximo) {
        return texto == null || texto.isBlank() || texto.length() > maximo;
    }
}
