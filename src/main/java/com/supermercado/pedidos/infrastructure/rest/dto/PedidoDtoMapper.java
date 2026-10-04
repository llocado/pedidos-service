package com.supermercado.pedidos.infrastructure.rest.dto;

import com.supermercado.pedidos.domain.model.ItemPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import org.springframework.stereotype.Component;

@Component
public class PedidoDtoMapper {

    public PedidoResponse toResponse(Pedido pedido) {
        return new PedidoResponse(
                pedido.getId().getValor().toString(),
                pedido.getCarritoIdOrigen().toString(),
                pedido.getUsuarioId(),
                pedido.getItems().stream().map(this::toResponse).toList(),
                pedido.getTotal(),
                pedido.getEstado().name(),
                pedido.getCreadoEn(),
                pedido.getActualizadoEn()
        );
    }

    private ItemPedidoResponse toResponse(ItemPedido item) {
        return new ItemPedidoResponse(
                item.getProductoId().toString(),
                item.getNombre(),
                item.getPrecioUnitario(),
                item.getMoneda(),
                item.getCantidad(),
                item.getSubtotal()
        );
    }
}
