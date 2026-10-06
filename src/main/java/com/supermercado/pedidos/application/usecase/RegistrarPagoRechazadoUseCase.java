package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB.
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrarPagoRechazadoUseCase {

    private final EventoProcesadoPort eventoProcesadoPort;

    /**
     * Un pago rechazado no cambia el pedido: queda en CREADO (decision del ROADMAP). Solo se deja constancia en el
     * log y se marca el evento como procesado. Idempotente por eventId.
     */
    @Transactional
    public void execute(UUID eventId, UUID pedidoId, String motivo) {
        if (eventoProcesadoPort.yaFueProcesado(eventId)) {
            log.info("Evento ya procesado, se descarta el duplicado eventId={}", eventId);
            return;
        }
        log.info("Pago rechazado, el pedido sigue pendiente de pago pedidoId={} motivo={}", pedidoId, motivo);
        eventoProcesadoPort.marcarComoProcesado(eventId);
    }
}
