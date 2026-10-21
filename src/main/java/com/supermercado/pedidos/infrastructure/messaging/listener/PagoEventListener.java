package com.supermercado.pedidos.infrastructure.messaging.listener;

import com.supermercado.pedidos.application.usecase.MarcarPedidoPagadoUseCase;
import com.supermercado.pedidos.application.usecase.RegistrarPagoRechazadoUseCase;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import com.supermercado.pedidos.infrastructure.messaging.dto.PagoAprobadoPayload;
import com.supermercado.pedidos.infrastructure.messaging.dto.PagoRechazadoPayload;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consume pago.aprobado y pago.rechazado de pagos-service. Misma politica de errores que CarritoEventListener:
 * payload invalido o pago incoherente con el pedido => DLQ sin reintentos; fallo transitorio (o pedido todavia no
 * visible) => se deja propagar y Spring reintenta 1 + 3 veces antes de mandarlo a la DLQ. Los logs llevan
 * eventId y correlationId, nunca el payload completo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PagoEventListener {

    static final int MAX_USUARIO_ID = 100;
    static final int MAX_MOTIVO = 50;
    private static final Pattern MONEDA = Pattern.compile("^[A-Z]{3}$");

    private final MarcarPedidoPagadoUseCase marcarPedidoPagadoUseCase;
    private final RegistrarPagoRechazadoUseCase registrarPagoRechazadoUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PAGO_APROBADO)
    public void escucharPagoAprobado(EventEnvelope envelope) {
        verificarEnvelope(envelope);
        try (MDC.MDCCloseable ignored = contexto(envelope)) {
            log.info("Evento recibido eventType={} eventId={}", envelope.eventType(), envelope.eventId());
            PagoAprobadoPayload payload = leerPayload(envelope, PagoAprobadoPayload.class);
            rechazarSiEsInvalido(envelope, validarAprobado(payload));
            try {
                marcarPedidoPagadoUseCase.execute(envelope.eventId(), payload.pedidoId(), payload.usuarioId(), payload.monto(), payload.moneda());
            } catch (PagoInconsistenteException e) {
                log.warn("Evento descartado a la DLQ eventId={}: pago incoherente con el pedido ({})", envelope.eventId(), e.getMessage());
                throw new AmqpRejectAndDontRequeueException("Pago incoherente con el pedido: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                log.warn("Fallo al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
                throw e;
            }
        }
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PAGO_RECHAZADO)
    public void escucharPagoRechazado(EventEnvelope envelope) {
        verificarEnvelope(envelope);
        try (MDC.MDCCloseable ignored = contexto(envelope)) {
            log.info("Evento recibido eventType={} eventId={}", envelope.eventType(), envelope.eventId());
            PagoRechazadoPayload payload = leerPayload(envelope, PagoRechazadoPayload.class);
            rechazarSiEsInvalido(envelope, validarRechazado(payload));
            try {
                registrarPagoRechazadoUseCase.execute(envelope.eventId(), payload.pedidoId(), payload.motivo());
            } catch (RuntimeException e) {
                log.warn("Fallo al procesar evento eventId={}: {}", envelope.eventId(), e.toString());
                throw e;
            }
        }
    }

    private void verificarEnvelope(EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.payload() == null) {
            log.warn("Evento descartado a la DLQ: falta eventId o payload");
            throw new AmqpRejectAndDontRequeueException("Envelope invalido: falta eventId o payload");
        }
    }

    private MDC.MDCCloseable contexto(EventEnvelope envelope) {
        UUID correlacion = envelope.correlationId() != null ? envelope.correlationId() : envelope.eventId();
        return MDC.putCloseable("correlationId", correlacion.toString());
    }

    private <T> T leerPayload(EventEnvelope envelope, Class<T> tipo) {
        try {
            return objectMapper.treeToValue(envelope.payload(), tipo);
        } catch (JacksonException e) {
            log.warn("Evento descartado a la DLQ eventId={}: payload no deserializable", envelope.eventId());
            throw new AmqpRejectAndDontRequeueException("Payload no deserializable", e);
        }
    }

    private void rechazarSiEsInvalido(EventEnvelope envelope, String motivo) {
        if (motivo != null) {
            log.warn("Evento descartado a la DLQ eventId={}: payload invalido ({})", envelope.eventId(), motivo);
            throw new AmqpRejectAndDontRequeueException("Payload invalido: " + motivo);
        }
    }

    /** Retorna el motivo si el payload es invalido, o null si esta bien. */
    static String validarAprobado(PagoAprobadoPayload payload) {
        if (payload == null) return "payload nulo";
        if (payload.pagoId() == null) return "falta pagoId";
        if (payload.pedidoId() == null) return "falta pedidoId";
        if (usuarioInvalido(payload.usuarioId())) return "usuarioId ausente o demasiado largo";
        if (payload.monto() == null || payload.monto().signum() <= 0) return "monto invalido";
        if (payload.moneda() == null || !MONEDA.matcher(payload.moneda()).matches()) return "moneda invalida";
        return null;
    }

    static String validarRechazado(PagoRechazadoPayload payload) {
        if (payload == null) return "payload nulo";
        if (payload.pedidoId() == null) return "falta pedidoId";
        if (payload.motivo() == null || payload.motivo().isBlank() || payload.motivo().length() > MAX_MOTIVO) {
            return "motivo ausente o demasiado largo";
        }
        return null;
    }

    private static boolean usuarioInvalido(String usuarioId) {
        return usuarioId == null || usuarioId.isBlank() || usuarioId.length() > MAX_USUARIO_ID;
    }
}
