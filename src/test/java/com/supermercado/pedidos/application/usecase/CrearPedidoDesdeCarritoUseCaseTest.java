package com.supermercado.pedidos.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import com.supermercado.pedidos.application.port.PedidoEventPublisherPort;
import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase.ItemCarritoComando;
import com.supermercado.pedidos.domain.model.Pedido;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CrearPedidoDesdeCarritoUseCaseTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID CARRITO_ID = UUID.randomUUID();
    private static final String USUARIO_ID = "usuario-123";

    @Mock
    private PedidoRepositoryPort pedidoRepositoryPort;

    @Mock
    private PedidoEventPublisherPort pedidoEventPublisherPort;

    @Mock
    private EventoProcesadoPort eventoProcesadoPort;

    private CrearPedidoDesdeCarritoUseCase crearPedidoDesdeCarritoUseCase;

    @BeforeEach
    void setUp() {
        crearPedidoDesdeCarritoUseCase =
                new CrearPedidoDesdeCarritoUseCase(pedidoRepositoryPort, pedidoEventPublisherPort, eventoProcesadoPort);
    }

    @Test
    void execute_DeberiaCrearYPublicarElPedido_CuandoElEventoNoFueProcesadoAntes() {
        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(false);
        when(pedidoRepositoryPort.guardar(any(Pedido.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<ItemCarritoComando> items = List.of(
                new ItemCarritoComando(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2));

        crearPedidoDesdeCarritoUseCase.execute(EVENT_ID, CARRITO_ID, USUARIO_ID, items);

        verify(pedidoRepositoryPort).guardar(any(Pedido.class));
        verify(pedidoEventPublisherPort).publicarPedidoCreado(any(Pedido.class));
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_NoDeberiaHacerNada_CuandoElEventoYaFueProcesado() {
        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(true);

        List<ItemCarritoComando> items = List.of(
                new ItemCarritoComando(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2));

        crearPedidoDesdeCarritoUseCase.execute(EVENT_ID, CARRITO_ID, USUARIO_ID, items);

        verify(pedidoRepositoryPort, never()).guardar(any(Pedido.class));
        verify(pedidoEventPublisherPort, never()).publicarPedidoCreado(any(Pedido.class));
        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }

    @Test
    void execute_DeberiaCalcularElTotalCorrectamente() {
        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(false);
        when(pedidoRepositoryPort.guardar(any(Pedido.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<ItemCarritoComando> items = List.of(
                new ItemCarritoComando(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2),
                new ItemCarritoComando(UUID.randomUUID(), "Pan", new BigDecimal("2000"), "CLP", 1));

        crearPedidoDesdeCarritoUseCase.execute(EVENT_ID, CARRITO_ID, USUARIO_ID, items);

        var captor = org.mockito.ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepositoryPort).guardar(captor.capture());
        assertThat(captor.getValue().getTotal()).isEqualByComparingTo("5000");
    }
}
