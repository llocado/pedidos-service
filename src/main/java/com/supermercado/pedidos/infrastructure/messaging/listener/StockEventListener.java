package com.supermercado.pedidos.infrastructure.messaging.listener;

import com.supermercado.pedidos.application.usecase.MarcarPedidoSinStockUseCase;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import com.supermercado.pedidos.infrastructure.messaging.dto.StockDescuentoRechazadoPayload;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consume stock.descuento-rechazado de app-productos. Misma politica de errores que PagoEventListener: payload
 * invalido o rechazo incoherente con el pedido => DLQ sin reintentos; fallo transitorio (o pedido todavia no PAGADO)
 * => se deja propagar y Spring reintenta 1 + 3 veces antes de mandarlo a la DLQ. Los logs llevan eventId y
 * correlationId, nunca el payload completo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventListener {

    static final int MAX_USUARIO_ID = 100;

    private final MarcarPedidoSinStockUseCase marcarPedidoSinStockUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_STOCK_DESCUENTO_RECHAZADO)
    public void escucharStockDescuentoRechazado(EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.payload() == null) {
            log.warn("Evento descartado a la DLQ: falta eventId o payload");
            throw new AmqpRejectAndDontRequeueException("Envelope invalido: falta eventId o payload");
        }
        UUID correlacion = envelope.correlationId() != null ? envelope.correlationId() : envelope.eventId();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlacion.toString())) {
            log.info("Evento recibido eventType={} eventId={}", envelope.eventType(), envelope.eventId());

            StockDescuentoRechazadoPayload payload = leerPayload(envelope);
            String motivo = validar(payload);
            if (motivo != null) {
                log.warn("Evento descartado a la DLQ eventId={}: payload invalido ({})", envelope.eventId(), motivo);
                throw new AmqpRejectAndDontRequeueException("Payload invalido: " + motivo);
            }
            try {
                marcarPedidoSinStockUseCase.execute(envelope.eventId(), payload.pedidoId(), payload.usuarioId());
            } catch (PagoInconsistenteException e) {
                log.warn("Evento descartado a la DLQ eventId={}: rechazo de stock incoherente con el pedido ({})", envelope.eventId(), e.getMessage());
                throw new AmqpRejectAndDontRequeueException("Rechazo de stock incoherente con el pedido: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                log.warn("Fallo al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
                throw e;
            }
        }
    }

    private StockDescuentoRechazadoPayload leerPayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.payload(), StockDescuentoRechazadoPayload.class);
        } catch (JacksonException e) {
            log.warn("Evento descartado a la DLQ eventId={}: payload no deserializable", envelope.eventId());
            throw new AmqpRejectAndDontRequeueException("Payload no deserializable", e);
        }
    }

    /** Retorna el motivo si el payload es invalido, o null si esta bien. */
    static String validar(StockDescuentoRechazadoPayload payload) {
        if (payload == null) return "payload nulo";
        if (payload.pagoId() == null) return "falta pagoId";
        if (payload.pedidoId() == null) return "falta pedidoId";
        if (payload.usuarioId() == null || payload.usuarioId().isBlank() || payload.usuarioId().length() > MAX_USUARIO_ID) {
            return "usuarioId ausente o demasiado largo";
        }
        if (payload.faltantes() == null || payload.faltantes().isEmpty()) return "sin faltantes";
        return null;
    }
}
