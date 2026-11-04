package com.supermercado.pedidos.infrastructure.scheduling;

import com.supermercado.pedidos.application.usecase.PurgarEventosAntiguosUseCase;
import com.supermercado.pedidos.infrastructure.config.PurgaProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/** Dispara la retencion de eventos segun purga.cron (por defecto, una vez al dia de madrugada). */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurgaEventosJob {

    private final PurgarEventosAntiguosUseCase purgarEventosAntiguosUseCase;
    private final PurgaProperties propiedades;

    @Scheduled(cron = "${purga.cron}")
    public void purgar() {
        try {
            PurgarEventosAntiguosUseCase.Resultado resultado = purgarEventosAntiguosUseCase.execute(
                    Duration.ofDays(propiedades.eventosProcesadosDias()), propiedades.lote());
            if (resultado.procesados() > 0) {
                log.info("Purga de eventos tabla=eventos_procesados borrados={}", resultado.procesados());
            }
        } catch (DataAccessException | TransactionException e) {
            // Con la base caida: una linea con la causa, sin traza. Se reintenta en la siguiente ejecucion programada.
            log.warn("Purga de eventos omitida, sin acceso a la base: {}",
                    NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
        }
    }
}
