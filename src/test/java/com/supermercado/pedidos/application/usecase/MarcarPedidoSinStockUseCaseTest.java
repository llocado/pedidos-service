package com.supermercado.pedidos.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.domain.model.EstadoPedido;
import com.supermercado.pedidos.domain.model.ItemPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarcarPedidoSinStockUseCaseTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String USUARIO_ID = "usuario-1";

    @Mock
    private PedidoRepositoryPort pedidoRepositoryPort;

    @Mock
    private EventoProcesadoPort eventoProcesadoPort;

    private MarcarPedidoSinStockUseCase useCase;
    private Pedido pedido;

    @BeforeEach
    void setUp() {
        useCase = new MarcarPedidoSinStockUseCase(pedidoRepositoryPort, eventoProcesadoPort);
        pedido = Pedido.crearDesdeCarrito(UUID.randomUUID(), USUARIO_ID,
                List.of(ItemPedido.de(UUID.randomUUID(), "Manzana", new BigDecimal("1500"), "CLP", 2)));
    }

    private UUID pedidoId() {
        return pedido.getId().getValor();
    }

    private void pedidoExiste() {
        when(pedidoRepositoryPort.buscarPorId(PedidoId.de(pedidoId()))).thenReturn(Optional.of(pedido));
    }

    @Test
    void execute_DeberiaPasarElPedidoPagadoASinStock_YMarcarElEventoProcesado() {
        pedido.transicionarA(EstadoPedido.PAGADO);
        pedidoExiste();

        useCase.execute(EVENT_ID, pedidoId(), USUARIO_ID);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
        verify(pedidoRepositoryPort).guardar(pedido);
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_DeberiaPasarElPedidoCreadoASinStock_CuandoElRechazoLlegaAntesQueElPago() {
        pedidoExiste();

        useCase.execute(EVENT_ID, pedidoId(), USUARIO_ID);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
        verify(pedidoRepositoryPort).guardar(pedido);
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_DeberiaFallarSinMarcarNada_CuandoElPedidoNoExiste() {
        when(pedidoRepositoryPort.buscarPorId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(EVENT_ID, UUID.randomUUID(), USUARIO_ID))
                .isInstanceOf(PedidoNoEncontradoException.class);

        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }

    @Test
    void execute_DeberiaRechazar_CuandoElUsuarioNoEsElDelPedido() {
        pedido.transicionarA(EstadoPedido.PAGADO);
        pedidoExiste();

        assertThatThrownBy(() -> useCase.execute(EVENT_ID, pedidoId(), "otro-usuario"))
                .isInstanceOf(PagoInconsistenteException.class);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.PAGADO);
        verify(pedidoRepositoryPort, never()).guardar(any());
        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }

    @Test
    void execute_NoDeberiaHacerNada_CuandoElEventoYaFueProcesado() {
        when(eventoProcesadoPort.yaFueProcesado(EVENT_ID)).thenReturn(true);

        useCase.execute(EVENT_ID, pedidoId(), USUARIO_ID);

        verify(pedidoRepositoryPort, never()).buscarPorId(any());
        verify(pedidoRepositoryPort, never()).guardar(any());
        verify(eventoProcesadoPort, never()).marcarComoProcesado(any());
    }

    @Test
    void execute_DeberiaIgnorarElEventoYMarcarloProcesado_CuandoElPedidoYaEstabaSinStock() {
        pedido.transicionarA(EstadoPedido.PAGADO);
        pedido.transicionarA(EstadoPedido.SIN_STOCK);
        pedidoExiste();

        useCase.execute(EVENT_ID, pedidoId(), USUARIO_ID);

        verify(pedidoRepositoryPort, never()).guardar(any());
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }

    @Test
    void execute_DeberiaIgnorarElEventoYMarcarloProcesado_CuandoElPedidoYaAvanzoMasAllaDePagado() {
        pedido.transicionarA(EstadoPedido.PAGADO);
        pedido.transicionarA(EstadoPedido.DESPACHADO);
        pedidoExiste();

        useCase.execute(EVENT_ID, pedidoId(), USUARIO_ID);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.DESPACHADO);
        verify(pedidoRepositoryPort, never()).guardar(any());
        verify(eventoProcesadoPort).marcarComoProcesado(EVENT_ID);
    }
}
