package com.supermercado.pedidos.infrastructure.persistence.adapter;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import com.supermercado.pedidos.infrastructure.persistence.entity.PedidoEntity;
import com.supermercado.pedidos.infrastructure.persistence.mapper.PedidoEntityMapper;
import com.supermercado.pedidos.infrastructure.persistence.repository.SpringDataPedidoRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las lecturas se anotan @Transactional aqui mismo (no solo en el caso de
 * uso que las invoca): mapear PedidoEntity a Pedido recorre la coleccion
 * lazy de items, y ese mapeo debe ocurrir con la sesion de Hibernate todavia
 * abierta sin importar en que hilo o contexto se invoque el puerto.
 */
@Component
@RequiredArgsConstructor
public class PedidoPersistenceAdapter implements PedidoRepositoryPort {

    private final SpringDataPedidoRepository springDataPedidoRepository;
    private final PedidoEntityMapper pedidoEntityMapper;

    @Override
    public Pedido guardar(Pedido pedido) {
        PedidoEntity entity = springDataPedidoRepository.findById(pedido.getId().getValor())
                .orElseGet(PedidoEntity::new);

        pedidoEntityMapper.volcarEnEntidad(pedido, entity);

        PedidoEntity guardada = springDataPedidoRepository.save(entity);
        return pedidoEntityMapper.toDomain(guardada);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Pedido> buscarPorId(PedidoId id) {
        return springDataPedidoRepository.findById(id.getValor())
                .map(pedidoEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pedido> buscarPorUsuarioId(String usuarioId) {
        return springDataPedidoRepository.findByUsuarioIdOrderByCreadoEnDesc(usuarioId).stream()
                .map(pedidoEntityMapper::toDomain)
                .toList();
    }
}
