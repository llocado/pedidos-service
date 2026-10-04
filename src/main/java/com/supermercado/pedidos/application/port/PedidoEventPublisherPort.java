package com.supermercado.pedidos.application.port;

import com.supermercado.pedidos.domain.model.Pedido;

public interface PedidoEventPublisherPort {

    void publicarPedidoCreado(Pedido pedido);
}
