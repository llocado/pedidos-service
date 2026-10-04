package com.supermercado.pedidos.infrastructure.messaging.listener;

import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase.ItemCarritoComando;
import com.supermercado.pedidos.domain.exception.PedidoSinItemsException;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload.ItemEventoDto;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
    static final int MAX_TEXTO = 255;
    static final int MAX_MONEDA = 3;

    private final CrearPedidoDesdeCarritoUseCase crearPedidoDesdeCarritoUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHECKOUT_INICIADO)
    public void escucharCheckoutIniciado(EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.payload() == null) {
            log.warn("Evento descartado a la DLQ: falta eventId o payload");
            throw new AmqpRejectAndDontRequeueException("Envelope invalido: falta eventId o payload");
        }

        String correlacion = String.valueOf(envelope.correlationId() != null ? envelope.correlationId() : envelope.eventId());
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlacion)) {
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
                crearPedidoDesdeCarritoUseCase.execute(envelope.eventId(), payload.carritoId(), payload.usuarioId(), items);
            } catch (IllegalArgumentException | PedidoSinItemsException e) {
                log.warn("Evento descartado a la DLQ eventId={}: regla de dominio violada ({})", envelope.eventId(), e.getMessage());
                throw new AmqpRejectAndDontRequeueException("Regla de dominio violada: " + e.getMessage(), e);
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
        if (textoInvalido(payload.usuarioId(), MAX_TEXTO)) return "usuarioId ausente o demasiado largo";
        return null;
    }

    private static String validarItems(List<ItemEventoDto> items) {
        if (items == null || items.isEmpty()) return "sin items";
        if (items.size() > MAX_ITEMS) return "demasiados items";
        for (ItemEventoDto item : items) {
            String motivo = validarItem(item);
            if (motivo != null) return motivo;
        }
        return null;
    }

    private static String validarItem(ItemEventoDto item) {
        if (item == null) return "item nulo";
        if (item.productoId() == null) return "item sin productoId";
        if (textoInvalido(item.nombre(), MAX_TEXTO)) return "item con nombre invalido";
        if (item.precioUnitario() == null || item.precioUnitario().signum() < 0) return "item con precio invalido";
        if (textoInvalido(item.moneda(), MAX_MONEDA)) return "item con moneda invalida";
        if (item.cantidad() <= 0) return "item con cantidad invalida";
        return null;
    }

    private static boolean textoInvalido(String texto, int maximo) {
        return texto == null || texto.isBlank() || texto.length() > maximo;
    }
}
