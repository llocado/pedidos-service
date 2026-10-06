package com.supermercado.pedidos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** DTO de deserializacion del payload de pago.rechazado (contrato: pagos-service/docs/events/pago.rechazado.schema.json). */
public record PagoRechazadoPayload(UUID pagoId, UUID pedidoId, String usuarioId, BigDecimal monto, String moneda, String motivo) {
}
