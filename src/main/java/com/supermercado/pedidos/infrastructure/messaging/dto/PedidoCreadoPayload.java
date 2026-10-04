package com.supermercado.pedidos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Payload publicado por pedidos-service en el evento pedido.creado. */
public record PedidoCreadoPayload(
        UUID pedidoId,
        UUID carritoId,
        String usuarioId,
        List<ItemEventoDto> items,
        BigDecimal total
) {

    public record ItemEventoDto(
            UUID productoId,
            String nombre,
            BigDecimal precioUnitario,
            String moneda,
            int cantidad
    ) {
    }
}
