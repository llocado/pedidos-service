package com.supermercado.pedidos.infrastructure.persistence.repository;

import com.supermercado.pedidos.infrastructure.persistence.entity.PedidoEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPedidoRepository extends JpaRepository<PedidoEntity, UUID> {

    List<PedidoEntity> findByUsuarioIdOrderByCreadoEnDesc(String usuarioId);
}
