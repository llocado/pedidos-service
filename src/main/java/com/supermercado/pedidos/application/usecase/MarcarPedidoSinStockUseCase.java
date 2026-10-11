package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.domain.exception.PedidoAunNoPagadoException;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.domain.model.EstadoPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB.
@Slf4j
@Service
@RequiredArgsConstructor
public class MarcarPedidoSinStockUseCase {

    private final PedidoRepositoryPort pedidoRepositoryPort;
    private final EventoProcesadoPort eventoProcesadoPort;

    /**
     * Reaccion a stock.descuento-rechazado: el pago se aprobo pero no habia stock para todo lo comprado. El pedido pasa
     * de PAGADO a SIN_STOCK para su gestion manual. Idempotente por eventId.
     *
     * <p>Un pedido inexistente o todavia en CREADO lanza una excepcion TRANSITORIA que el listener deja propagar para
     * que se reintente: pago.aprobado llega en paralelo a pedidos y a productos, asi que este evento puede adelantarse.
     * Un usuario distinto al del pedido es un dato incoherente (PagoInconsistenteException): no se reintenta.
     */
    @Transactional
    public void execute(UUID eventId, UUID pedidoId, String usuarioId) {
        if (eventoProcesadoPort.yaFueProcesado(eventId)) {
            log.info("Evento ya procesado, se descarta el duplicado eventId={}", eventId);
            return;
        }
        Pedido pedido = pedidoRepositoryPort.buscarPorId(PedidoId.de(pedidoId))
                .orElseThrow(() -> new PedidoNoEncontradoException("Pedido no encontrado: " + pedidoId));
        if (!pedido.getUsuarioId().equals(usuarioId)) {
            throw new PagoInconsistenteException("El rechazo de stock no corresponde al usuario del pedido " + pedidoId);
        }
        switch (pedido.getEstado()) {
            case CREADO -> throw new PedidoAunNoPagadoException("El pedido " + pedidoId + " aun no esta PAGADO");
            case PAGADO -> {
                pedido.transicionarA(EstadoPedido.SIN_STOCK);
                pedidoRepositoryPort.guardar(pedido);
                log.warn("Pedido sin stock, requiere gestion manual pedidoId={} usuarioId={}", pedidoId, usuarioId);
            }
            case SIN_STOCK -> log.info("El pedido ya estaba SIN_STOCK, se ignora el evento pedidoId={}", pedidoId);
            default -> log.warn("Rechazo de stock para un pedido que ya avanzo, se ignora pedidoId={} estado={}", pedidoId, pedido.getEstado());
        }
        eventoProcesadoPort.marcarComoProcesado(eventId);
    }
}
