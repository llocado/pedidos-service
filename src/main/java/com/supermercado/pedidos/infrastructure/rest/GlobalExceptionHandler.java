package com.supermercado.pedidos.infrastructure.rest;

import com.supermercado.pedidos.domain.exception.PedidoNoEncontradoException;
import com.supermercado.pedidos.infrastructure.rest.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PedidoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> handlePedidoNoEncontrado(PedidoNoEncontradoException ex) {
        log.info("Pedido no encontrado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }
}
