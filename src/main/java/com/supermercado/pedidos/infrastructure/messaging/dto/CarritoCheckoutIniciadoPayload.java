package com.supermercado.pedidos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTO de deserializacion del payload de carrito.checkout-iniciado, armado a
 * partir del JSON Schema documentado en docs/events/carrito.checkout-iniciado.schema.json.
 * pedidos-service es el consumidor: arma su propio DTO en vez de importar
 * nada de carrito-service (sin libreria de contrato compartida, por diseno).
 */
public record CarritoCheckoutIniciadoPayload(
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
