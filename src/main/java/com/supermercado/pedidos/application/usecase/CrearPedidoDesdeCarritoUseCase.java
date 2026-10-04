package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import com.supermercado.pedidos.application.port.PedidoEventPublisherPort;
import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.model.ItemPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CrearPedidoDesdeCarritoUseCase {

    private final PedidoRepositoryPort pedidoRepositoryPort;
    private final PedidoEventPublisherPort pedidoEventPublisherPort;
    private final EventoProcesadoPort eventoProcesadoPort;

    /**
     * Idempotente por eventId: si el evento carrito.checkout-iniciado que
     * origina esta llamada ya fue procesado (reintento de RabbitMQ tras un
     * ack perdido, entrega at-least-once), no crea un segundo pedido.
     */
    @Transactional
    public void execute(UUID eventId, UUID carritoId, String usuarioId, List<ItemCarritoComando> items) {
        if (eventoProcesadoPort.yaFueProcesado(eventId)) {
            return;
        }

        List<ItemPedido> itemsPedido = items.stream()
                .map(item -> ItemPedido.de(item.productoId(), item.nombre(), item.precioUnitario(), item.moneda(), item.cantidad()))
                .toList();

        Pedido pedido = Pedido.crearDesdeCarrito(carritoId, usuarioId, itemsPedido);
        pedido = pedidoRepositoryPort.guardar(pedido);

        pedidoEventPublisherPort.publicarPedidoCreado(pedido);
        eventoProcesadoPort.marcarComoProcesado(eventId);
    }

    public record ItemCarritoComando(UUID productoId, String nombre, BigDecimal precioUnitario, String moneda, int cantidad) {
    }
}
