package com.supermercado.pedidos.application.port;

import com.supermercado.pedidos.domain.model.Pedido;
import java.util.UUID;

public interface PedidoEventPublisherPort {

    /** correlationId es el id del checkout: viaja en el envelope para poder seguir el flujo entre servicios. */
    void publicarPedidoCreado(Pedido pedido, UUID correlationId);
}
