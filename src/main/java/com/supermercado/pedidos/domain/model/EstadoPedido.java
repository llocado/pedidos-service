package com.supermercado.pedidos.domain.model;

import java.util.List;

/**
 * Ciclo de vida del pedido. Si el pago es rechazado, el pedido no tiene un
 * estado terminal propio -- simplemente queda en CREADO (decision del
 * ROADMAP: "alcanza con que el pedido quede pendiente si el pago falla").
 */
public enum EstadoPedido {
    CREADO,
    PAGADO,
    DESPACHADO,
    ENTREGADO;

    private static final List<EstadoPedido> ORDEN = List.of(CREADO, PAGADO, DESPACHADO, ENTREGADO);

    public boolean puedeTransicionarA(EstadoPedido siguiente) {
        int posicionActual = ORDEN.indexOf(this);
        int posicionSiguiente = ORDEN.indexOf(siguiente);
        return posicionSiguiente == posicionActual + 1;
    }
}
