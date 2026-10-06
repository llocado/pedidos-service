package com.supermercado.pedidos.infrastructure.messaging.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO de deserializacion del payload de pago.aprobado, armado a partir del JSON Schema que publica
 * pagos-service (docs/events/pago.aprobado.schema.json). Solo los campos que pedidos necesita: pedidos-service
 * no importa nada de otro servicio y los campos desconocidos se ignoran (evolucion aditiva del contrato).
 */
public record PagoAprobadoPayload(UUID pagoId, UUID pedidoId, String usuarioId, BigDecimal monto, String moneda) {
}
