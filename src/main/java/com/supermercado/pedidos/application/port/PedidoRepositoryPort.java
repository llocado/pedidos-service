package com.supermercado.pedidos.application.port;

import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import java.util.List;
import java.util.Optional;

public interface PedidoRepositoryPort {

    Pedido guardar(Pedido pedido);

    Optional<Pedido> buscarPorId(PedidoId id);

    List<Pedido> buscarPorUsuarioId(String usuarioId);
}
