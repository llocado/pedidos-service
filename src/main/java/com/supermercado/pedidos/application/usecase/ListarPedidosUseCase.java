package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.model.Pedido;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB
// (subclase en tiempo de ejecucion), y CGLIB no puede subclasificar una clase final.
@Service
@RequiredArgsConstructor
public class ListarPedidosUseCase {

    private final PedidoRepositoryPort pedidoRepositoryPort;

    /**
     * Solo-lectura, pero igual necesita una transaccion activa: sin ella, el
     * mapper de persistencia no puede leer la coleccion de items (lazy) -- la
     * sesion de Hibernate ya se cerro para cuando se intenta el mapeo.
     */
    @Transactional(readOnly = true)
    public List<Pedido> execute(String usuarioId) {
        return pedidoRepositoryPort.buscarPorUsuarioId(usuarioId);
    }
}
