package com.supermercado.pedidos.domain.exception;

/**
 * Llego un evento de stock para un pedido que todavia esta en CREADO. pago.aprobado llega en paralelo a pedidos y a
 * productos: el rechazo de stock puede adelantarse al pago.aprobado que pasa el pedido a PAGADO. Es transitorio, no
 * un dato incoherente: el listener lo deja propagar para que se REINTENTE (1 + 3) y entonces el pedido ya este PAGADO.
 */
public class PedidoAunNoPagadoException extends RuntimeException {

    public PedidoAunNoPagadoException(String message) {
        super(message);
    }
}
