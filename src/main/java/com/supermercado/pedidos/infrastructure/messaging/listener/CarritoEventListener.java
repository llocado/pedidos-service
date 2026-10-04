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
        if (payload.carritoId() == null) return "falta carritoId";
        if (payload.usuarioId() == null || payload.usuarioId().isBlank()) return "falta usuarioId";
        if (payload.usuarioId().length() > MAX_TEXTO) return "usuarioId demasiado largo";
        if (payload.items() == null || payload.items().isEmpty()) return "sin items";
        if (payload.items().size() > MAX_ITEMS) return "demasiados items";
        for (ItemEventoDto item : payload.items()) {
            if (item == null) return "item nulo";
            if (item.productoId() == null) return "item sin productoId";
            if (item.nombre() == null || item.nombre().isBlank() || item.nombre().length() > MAX_TEXTO) return "item con nombre invalido";
            if (item.precioUnitario() == null || item.precioUnitario().signum() < 0) return "item con precio invalido";
            if (item.moneda() == null || item.moneda().isBlank() || item.moneda().length() > 3) return "item con moneda invalida";
            if (item.cantidad() <= 0) return "item con cantidad invalida";
        }
        return null;
    }
}
