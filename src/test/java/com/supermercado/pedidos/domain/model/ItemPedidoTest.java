package com.supermercado.pedidos.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemPedidoTest {

    @Test
    void getSubtotal_DeberiaMultiplicarPrecioPorCantidad() {
        ItemPedido item = ItemPedido.de(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 3);

        assertThat(item.getSubtotal()).isEqualByComparingTo("4500");
    }

    @Test
    void de_DeberiaLanzarExcepcion_CuandoLaCantidadNoEsPositiva() {
        assertThatThrownBy(() -> ItemPedido.de(UUID.randomUUID(), "Manzana Fuji", new BigDecimal("1500"), "CLP", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
