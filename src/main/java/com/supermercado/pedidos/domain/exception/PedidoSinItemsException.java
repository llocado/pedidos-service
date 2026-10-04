package com.supermercado.pedidos.domain.exception;

public class PedidoSinItemsException extends RuntimeException {

    public PedidoSinItemsException(String message) {
        super(message);
    }
}
