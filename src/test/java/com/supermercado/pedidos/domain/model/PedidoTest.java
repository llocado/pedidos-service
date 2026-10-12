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

    @Test
    void transicionarA_DeberiaPasarASinStock_DesdePagado() {
        Pedido pedido = pedidoConUnItem();
        pedido.transicionarA(EstadoPedido.PAGADO);

        pedido.transicionarA(EstadoPedido.SIN_STOCK);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
    }

    @Test
    void transicionarA_DeberiaPasarASinStock_DesdeCreado_PorqueElRechazoPuedeAdelantarseAlPago() {
        Pedido pedido = pedidoConUnItem();

        pedido.transicionarA(EstadoPedido.SIN_STOCK);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
    }

    @Test
    void transicionarA_NoDeberiaPasarASinStock_DesdeDespachadoOEntregado() {
        for (EstadoPedido desde : new EstadoPedido[] {EstadoPedido.DESPACHADO, EstadoPedido.ENTREGADO}) {
            Pedido pedido = pedidoEn(desde);

            assertThatThrownBy(() -> pedido.transicionarA(EstadoPedido.SIN_STOCK))
                    .as("desde %s", desde)
                    .isInstanceOf(TransicionEstadoInvalidaException.class);
        }
    }

    @Test
    void transicionarA_NoDeberiaSalirDeSinStock_HaciaNingunEstado() {
        Pedido pedido = pedidoEn(EstadoPedido.SIN_STOCK);

        // Sin la regla explicita, indexOf(SIN_STOCK) = -1 dejaria "avanzar" a CREADO (posicion 0).
        for (EstadoPedido destino : EstadoPedido.values()) {
            assertThatThrownBy(() -> pedido.transicionarA(destino))
                    .as("SIN_STOCK -> %s", destino)
                    .isInstanceOf(TransicionEstadoInvalidaException.class);
        }
        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
    }

    private static Pedido pedidoEn(EstadoPedido estado) {
        Pedido pedido = pedidoConUnItem();
        switch (estado) {
            case CREADO -> { }
            case PAGADO -> pedido.transicionarA(EstadoPedido.PAGADO);
            case DESPACHADO -> {
                pedido.transicionarA(EstadoPedido.PAGADO);
                pedido.transicionarA(EstadoPedido.DESPACHADO);
            }
            case ENTREGADO -> {
                pedido.transicionarA(EstadoPedido.PAGADO);
                pedido.transicionarA(EstadoPedido.DESPACHADO);
                pedido.transicionarA(EstadoPedido.ENTREGADO);
            }
            case SIN_STOCK -> {
                pedido.transicionarA(EstadoPedido.PAGADO);
                pedido.transicionarA(EstadoPedido.SIN_STOCK);
            }
        }
        return pedido;
    }

    private static Pedido pedidoConUnItem() {
        ItemPedido item = ItemPedido.de(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 2);
        return Pedido.crearDesdeCarrito(CARRITO_ID, USUARIO_ID, List.of(item));
    }
}
