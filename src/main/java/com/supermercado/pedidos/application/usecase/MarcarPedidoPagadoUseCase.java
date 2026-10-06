package com.supermercado.pedidos.application.usecase;

import com.supermercado.pedidos.application.port.EventoProcesadoPort;
import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.exception.PagoInconsistenteException;
import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.domain.model.EstadoPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// No es "final": @Transactional necesita que Spring genere un proxy CGLIB.
@Slf4j
@Service
@RequiredArgsConstructor
public class MarcarPedidoPagadoUseCase {

    private final PedidoRepositoryPort pedidoRepositoryPort;
    private final EventoProcesadoPort eventoProcesadoPort;

    /**
     * Idempotente por eventId. Un pedido inexistente lanza PedidoNoEncontradoException, que el listener deja
     * propagar para que se REINTENTE: pedido.creado se publica antes de que el pedido se confirme en la base,
     * asi que pago.aprobado puede llegar un instante antes de que el pedido sea visible. El usuario o el monto
     * distintos a los del pedido son datos incoherentes (PagoInconsistenteException): no se reintentan.
     */
    @Transactional
    public void execute(UUID eventId, UUID pedidoId, String usuarioId, BigDecimal monto) {
        if (eventoProcesadoPort.yaFueProcesado(eventId)) {
            log.info("Evento ya procesado, se descarta el duplicado eventId={}", eventId);
            return;
        }

        Pedido pedido = pedidoRepositoryPort.buscarPorId(PedidoId.de(pedidoId))
                .orElseThrow(() -> new PedidoNoEncontradoException("Pedido no encontrado: " + pedidoId));
        if (!pedido.getUsuarioId().equals(usuarioId)) {
            throw new PagoInconsistenteException("El pago no corresponde al usuario del pedido " + pedidoId);
        }
        if (pedido.getTotal().compareTo(monto) != 0) {
            throw new PagoInconsistenteException("El monto del pago no coincide con el total del pedido " + pedidoId);
        }

        if (pedido.getEstado() == EstadoPedido.CREADO) {
            pedido.transicionarA(EstadoPedido.PAGADO);
            pedidoRepositoryPort.guardar(pedido);
            log.info("Pedido pagado pedidoId={} usuarioId={} monto={}", pedidoId, usuarioId, monto);
        } else {
            log.info("El pedido ya no esta pendiente de pago, se ignora el evento pedidoId={} estado={}", pedidoId, pedido.getEstado());
        }
        eventoProcesadoPort.marcarComoProcesado(eventId);
    }
}
