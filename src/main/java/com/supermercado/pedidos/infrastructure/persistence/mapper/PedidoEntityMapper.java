package com.supermercado.pedidos.infrastructure.persistence.mapper;

import com.supermercado.pedidos.domain.model.EstadoPedido;
import com.supermercado.pedidos.domain.model.ItemPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import com.supermercado.pedidos.infrastructure.persistence.entity.ItemPedidoEntity;
import com.supermercado.pedidos.infrastructure.persistence.entity.PedidoEntity;
import com.supermercado.pedidos.infrastructure.persistence.entity.PedidoEntity.EstadoPedidoJpa;
import org.springframework.stereotype.Component;

@Component
public class PedidoEntityMapper {

    /**
     * A diferencia de Carrito, los items de un Pedido no cambian tras crearlo
     * -- solo el estado transiciona. Por eso la reconciliacion de la
     * coleccion de items solo corre en la primera persistencia (entidad
     * nueva); en las siguientes solo se actualizan los campos mutables.
     */
    public void volcarEnEntidad(Pedido pedido, PedidoEntity entity) {
        boolean esNueva = entity.getId() == null;

        entity.setId(pedido.getId().getValor());
        entity.setCarritoIdOrigen(pedido.getCarritoIdOrigen());
        entity.setUsuarioId(pedido.getUsuarioId());
        entity.setTotal(pedido.getTotal());
        entity.setEstado(EstadoPedidoJpa.valueOf(pedido.getEstado().name()));
        entity.setCreadoEn(pedido.getCreadoEn());
        entity.setActualizadoEn(pedido.getActualizadoEn());

        if (esNueva) {
            for (ItemPedido item : pedido.getItems()) {
                ItemPedidoEntity itemEntity = new ItemPedidoEntity();
                itemEntity.setPedido(entity);
                itemEntity.setProductoId(item.getProductoId());
                itemEntity.setNombre(item.getNombre());
                itemEntity.setPrecioUnitario(item.getPrecioUnitario());
                itemEntity.setMoneda(item.getMoneda());
                itemEntity.setCantidad(item.getCantidad());
                entity.getItems().add(itemEntity);
            }
        }
    }

    public Pedido toDomain(PedidoEntity entity) {
        var items = entity.getItems().stream()
                .map(itemEntity -> ItemPedido.de(
                        itemEntity.getProductoId(),
                        itemEntity.getNombre(),
                        itemEntity.getPrecioUnitario(),
                        itemEntity.getMoneda(),
                        itemEntity.getCantidad()))
                .toList();

        return Pedido.reconstruir(
                PedidoId.de(entity.getId()),
                entity.getCarritoIdOrigen(),
                entity.getUsuarioId(),
                items,
                entity.getTotal(),
                EstadoPedido.valueOf(entity.getEstado().name()),
                entity.getCreadoEn(),
                entity.getActualizadoEn()
        );
    }
}
