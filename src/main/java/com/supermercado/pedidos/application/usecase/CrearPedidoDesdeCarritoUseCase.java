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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
     * El eventId se reserva ANTES de guardar y publicar: con dos entregas simultaneas (varias
     * instancias o consumidores concurrentes) la que pierde choca con la unica de
     * eventos_procesados y se revierte sin haber publicado nada; si se reservara al final, ya
     * habria emitido un pedido.creado de un pedido que no existe.
     */
    @Transactional
    public void execute(UUID eventId, UUID correlationId, UUID carritoId, String usuarioId, List<ItemCarritoComando> items) {
        if (eventoProcesadoPort.yaFueProcesado(eventId)) {
            log.info("Evento ya procesado, se descarta el duplicado eventId={}", eventId);
            return;
        }

        eventoProcesadoPort.marcarComoProcesado(eventId);

        List<ItemPedido> itemsPedido = items.stream()
                .map(item -> ItemPedido.de(item.productoId(), item.nombre(), item.precioUnitario(), item.moneda(), item.cantidad()))
                .toList();

        Pedido pedido = Pedido.crearDesdeCarrito(carritoId, usuarioId, itemsPedido);
        pedido = pedidoRepositoryPort.guardar(pedido);

        pedidoEventPublisherPort.publicarPedidoCreado(pedido, correlationId);
        log.info("Pedido creado pedidoId={} carritoId={} usuarioId={} items={} total={}",
                pedido.getId().getValor(), carritoId, usuarioId, itemsPedido.size(), pedido.getTotal());
    }

    /** Para el listener: tras un choque de unicidad concurrente, distingue "duplicado" de un error real. */
    @Transactional(readOnly = true)
    public boolean yaProcesado(UUID eventId) {
        return eventoProcesadoPort.yaFueProcesado(eventId);
    }

    public record ItemCarritoComando(UUID productoId, String nombre, BigDecimal precioUnitario, String moneda, int cantidad) {
    }
}
