package com.supermercado.pedidos.domain.exception;

public class TransicionEstadoInvalidaException extends RuntimeException {

    public TransicionEstadoInvalidaException(String message) {
        super(message);
    }
}
