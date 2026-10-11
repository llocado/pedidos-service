package com.supermercado.pedidos.domain.model;

import java.util.List;

/**
 * Ciclo de vida del pedido. Si el pago es rechazado, el pedido no tiene un
 * estado terminal propio -- simplemente queda en CREADO (decision del
 * ROADMAP: "alcanza con que el pedido quede pendiente si el pago falla").
 *
 * SIN_STOCK es una rama lateral, no un paso del recorrido: el pago se aprobo pero
 * al descontar el stock algun producto ya no alcanzaba. Solo se llega desde PAGADO
 * y es terminal (se gestiona a mano: reponer, sustituir o devolver el dinero).
 */
public enum EstadoPedido {
    CREADO,
    PAGADO,
    DESPACHADO,
    ENTREGADO,
    SIN_STOCK;

    private static final List<EstadoPedido> ORDEN = List.of(CREADO, PAGADO, DESPACHADO, ENTREGADO);

    public boolean puedeTransicionarA(EstadoPedido siguiente) {
        // SIN_STOCK no esta en ORDEN (indexOf daria -1 y la regla de "avanzar un paso" permitiria SIN_STOCK -> CREADO).
        if (siguiente == SIN_STOCK) {
            return this == PAGADO;
        }
        if (this == SIN_STOCK) {
            return false;
        }
        int posicionActual = ORDEN.indexOf(this);
        int posicionSiguiente = ORDEN.indexOf(siguiente);
        return posicionSiguiente == posicionActual + 1;
    }
}
