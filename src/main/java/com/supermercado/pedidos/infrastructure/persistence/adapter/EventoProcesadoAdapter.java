package com.supermercado.pedidos.infrastructure.persistence.adapter;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import com.supermercado.pedidos.infrastructure.persistence.entity.EventoProcesadoEntity;
import com.supermercado.pedidos.infrastructure.persistence.repository.SpringDataEventoProcesadoRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventoProcesadoAdapter implements EventoProcesadoPort {

    private final SpringDataEventoProcesadoRepository springDataEventoProcesadoRepository;

    @Override
    public boolean yaFueProcesado(UUID eventId) {
        return springDataEventoProcesadoRepository.existsById(eventId);
    }

    @Override
    public void marcarComoProcesado(UUID eventId) {
        EventoProcesadoEntity entity = new EventoProcesadoEntity();
        entity.setEventId(eventId);
        entity.setProcesadoEn(Instant.now());
        springDataEventoProcesadoRepository.save(entity);
    }
}
