package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Retencion de {@code eventos_procesados} (idempotencia), que de otro modo creceria sin limite: se borra lo anotado hace
 * mas de {@code retencionProcesados}. El plazo debe ser MAYOR que lo que un duplicado puede esperar en una cola (por
 * ejemplo, con un consumidor caido): si se borra antes, un duplicado tardio se procesaria otra vez.
 * Borra en lotes (cada lote es su propia transaccion en el adaptador) hasta que uno viene incompleto.
 */
@Service
@RequiredArgsConstructor
public class PurgarEventosAntiguosUseCase {

    private final EventoProcesadoPort eventoProcesadoPort;
    private final Clock clock;

    /** Cuantos registros se borraron. */
    public record Resultado(int procesados) {
    }

    public Resultado execute(Duration retencionProcesados, int lote) {
        Instant limite = clock.instant().minus(retencionProcesados);
        int total = 0;
        int borrados;
        do {
            borrados = eventoProcesadoPort.eliminarProcesadosAnterioresA(limite, lote);
            total += borrados;
        } while (borrados == lote);
        return new Resultado(total);
    }
}
