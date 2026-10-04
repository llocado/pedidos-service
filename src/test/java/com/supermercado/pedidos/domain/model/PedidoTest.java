package com.supermercado.pedidos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supermercado.pedidos.domain.exception.PedidoSinItemsException;
import com.supermercado.pedidos.domain.exception.TransicionEstadoInvalidaException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PedidoTest {

    private static final UUID CARRITO_ID = UUID.randomUUID();
    private static final String USUARIO_ID = "usuario-123";

    @Test
    void crearDesdeCarrito_DeberiaCalcularElTotalYQuedarEnCreado() {
        ItemPedido item1 = ItemPedido.de(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2);
        ItemPedido item2 = ItemPedido.de(UUID.randomUUID(), "Pan", new BigDecimal("2000"), "CLP", 1);

        Pedido pedido = Pedido.crearDesdeCarrito(CARRITO_ID, USUARIO_ID, List.of(item1, item2));

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.CREADO);
        assertThat(pedido.getTotal()).isEqualByComparingTo("5000");
        assertThat(pedido.getItems()).hasSize(2);
        assertThat(pedido.getCarritoIdOrigen()).isEqualTo(CARRITO_ID);
    }

    @Test
    void crearDesdeCarrito_DeberiaLanzarExcepcion_CuandoNoHayItems() {
        assertThatThrownBy(() -> Pedido.crearDesdeCarrito(CARRITO_ID, USUARIO_ID, List.of()))
                .isInstanceOf(PedidoSinItemsException.class);
    }

    @Test
    void transicionarA_DeberiaAvanzarElEstado_CuandoLaTransicionEsValida() {
        Pedido pedido = pedidoConUnItem();

        pedido.transicionarA(EstadoPedido.PAGADO);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.PAGADO);
    }

    @Test
    void transicionarA_DeberiaLanzarExcepcion_CuandoSeSaltaUnEstado() {
        Pedido pedido = pedidoConUnItem();

        assertThatThrownBy(() -> pedido.transicionarA(EstadoPedido.DESPACHADO))
                .isInstanceOf(TransicionEstadoInvalidaException.class);
    }

    @Test
    void transicionarA_DeberiaLanzarExcepcion_CuandoRetrocedeDeEstado() {
        Pedido pedido = pedidoConUnItem();
        pedido.transicionarA(EstadoPedido.PAGADO);

        assertThatThrownBy(() -> pedido.transicionarA(EstadoPedido.CREADO))
                .isInstanceOf(TransicionEstadoInvalidaException.class);
    }

    private static Pedido pedidoConUnItem() {
        ItemPedido item = ItemPedido.de(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2);
        return Pedido.crearDesdeCarrito(CARRITO_ID, USUARIO_ID, List.of(item));
    }
}
