package com.supermercado.pedidos.domain.exception;

/** El pago no corresponde al pedido (usuario o monto distintos): el evento es irrecuperable, no se reintenta. */
public class PagoInconsistenteException extends RuntimeException {

    public PagoInconsistenteException(String message) {
        super(message);
    }
}
