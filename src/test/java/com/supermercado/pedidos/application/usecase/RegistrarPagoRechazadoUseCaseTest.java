package com.supermercado.pedidos.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistrarPagoRechazadoUseCaseTest {

    private static final UUID EVENT_ID = UUID.randomUUID();

    @Mock
    private EventoProcesadoPort eventoProcesadoPort;

    @Test
    void execute_DeberiaMarcarElEventoComoProcesado_SinTocarElPedido() {
        new RegistrarPagoRechazadoUseCase(eventoProcesadoPort).execute(EVENT_ID, UUID.randomUUID(), "MONTO_EXCEDE_LIMITE");

        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_NoDeberiaHacerNada_CuandoElEventoYaFueProcesado() {
        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(true);

        new RegistrarPagoRechazadoUseCase(eventoProcesadoPort).execute(EVENT_ID, UUID.randomUUID(), "MONTO_EXCEDE_LIMITE");

        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }
}
