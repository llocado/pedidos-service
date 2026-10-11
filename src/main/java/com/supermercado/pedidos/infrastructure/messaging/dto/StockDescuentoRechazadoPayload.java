package com.supermercado.pedidos.infrastructure.messaging.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO de deserializacion del payload de stock.descuento-rechazado, armado a partir del JSON Schema que publica
 * app-productos (docs/events/stock.descuento-rechazado.schema.json). Los faltantes solo se cuentan para el log: el
 * detalle (que producto y cuanto) lo ve quien gestiona el pedido en el evento y en los logs de productos.
 */
public record StockDescuentoRechazadoPayload(UUID pagoId, UUID pedidoId, String usuarioId, List<FaltanteDto> faltantes) {

    public record FaltanteDto(UUID productoId, int solicitado, int disponible) {
    }
}
