package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB
// (subclase en tiempo de ejecucion), y CGLIB no puede subclasificar una clase final.
@Service
@RequiredArgsConstructor
public class ObtenerPedidoUseCase {

    private final PedidoRepositoryPort pedidoRepositoryPort;

    /**
     * Se exige usuarioId ademas del id de pedido para que un usuario no pueda
     * ver el pedido de otro adivinando su UUID: si el pedido existe pero es
     * de otro usuario, se responde igual que "no existe" (evita filtrar que
     * el id es valido). Solo-lectura, pero igual necesita una transaccion
     * activa: sin ella, el mapper de persistencia no puede leer la coleccion
     * de items (lazy) -- la sesion de Hibernate ya se cerro para cuando se
     * intenta el mapeo.
     */
    @Transactional(readOnly = true)
    public Pedido execute(PedidoId id, String usuarioId) {
        Pedido pedido = pedidoRepositoryPort.buscarPorId(id)
                .orElseThrow(() -> new PedidoNoEncontradoException("Pedido no encontrado: " + id));
        if (!pedido.getUsuarioId().equals(usuarioId)) {
            throw new PedidoNoEncontradoException("Pedido no encontrado: " + id);
        }
        return pedido;
    }
}
