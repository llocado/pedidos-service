package com.supermercado.pedidos.infrastructure.rest.dto;

import java.math.BigDecimal;

public record ItemPedidoResponse(
        String productoId,
        String nombre,
        BigDecimal precioUnitario,
        String moneda,
        int cantidad,
        BigDecimal subtotal
) {
}
