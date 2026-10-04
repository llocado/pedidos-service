package com.supermercado.pedidos.domain.exception;

public class PedidoNoEncontradoException extends RuntimeException {

    public PedidoNoEncontradoException(String message) {
        super(message);
    }
}
