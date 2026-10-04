package com.supermercado.pedidos.infrastructure.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PedidoResponse(
        String id,
        String carritoIdOrigen,
        String usuarioId,
        List<ItemPedidoResponse> items,
        BigDecimal total,
        String estado,
        Instant creadoEn,
        Instant actualizadoEn
) {
}
