package com.supermercado.pedidos.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
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
class ObtenerPedidoUseCaseTest {

    private static final String USUARIO_ID = "usuario-123";

    @Mock
    private PedidoRepositoryPort pedidoRepositoryPort;

    private ObtenerPedidoUseCase obtenerPedidoUseCase;

    @BeforeEach
    void setUp() {
        obtenerPedidoUseCase = new ObtenerPedidoUseCase(pedidoRepositoryPort);
    }

    @Test
    void execute_DeberiaRetornarElPedido_CuandoExisteYEsDelUsuario() {
        Pedido pedido = pedidoDe(USUARIO_ID);
        when(pedidoRepositoryPort.buscarPorId(pedido.getId())).thenReturn(Optional.of(pedido));

        Pedido resultado = obtenerPedidoUseCase.execute(pedido.getId(), USUARIO_ID);

        assertThat(resultado).isEqualTo(pedido);
    }

    @Test
    void execute_DeberiaLanzarExcepcion_CuandoNoExiste() {
        PedidoId id = PedidoId.nuevo();
        when(pedidoRepositoryPort.buscarPorId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> obtenerPedidoUseCase.execute(id, USUARIO_ID))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    @Test
    void execute_DeberiaLanzarExcepcion_CuandoElPedidoEsDeOtroUsuario() {
        Pedido pedido = pedidoDe("otro-usuario");
        when(pedidoRepositoryPort.buscarPorId(pedido.getId())).thenReturn(Optional.of(pedido));

        assertThatThrownBy(() -> obtenerPedidoUseCase.execute(pedido.getId(), USUARIO_ID))
                .isInstanceOf(PedidoNoEncontradoException.class);
    }

    private static Pedido pedidoDe(String usuarioId) {
        ItemPedido item = ItemPedido.de(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2);
        return Pedido.crearDesdeCarrito(UUID.randomUUID(), usuarioId, List.of(item));
    }
}
