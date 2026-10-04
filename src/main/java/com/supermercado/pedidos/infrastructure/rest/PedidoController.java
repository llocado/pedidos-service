package com.supermercado.pedidos.infrastructure.rest;

import com.supermercado.pedidos.application.usecase.ListarPedidosUseCase;
import com.supermercado.pedidos.application.usecase.ObtenerPedidoUseCase;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import com.supermercado.pedidos.infrastructure.rest.dto.PedidoDtoMapper;
import com.supermercado.pedidos.infrastructure.rest.dto.PedidoResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Solo lectura: la creacion de un pedido no ocurre via HTTP, sino a partir
 * del evento carrito.checkout-iniciado (ver CarritoEventListener). El id de
 * usuario nunca viene del cliente -- se saca del claim "sub" del JWT.
 */
@RestController
@RequestMapping("/api/pedidos")
@RequiredArgsConstructor
public class PedidoController {

    private final ObtenerPedidoUseCase obtenerPedidoUseCase;
    private final ListarPedidosUseCase listarPedidosUseCase;
    private final PedidoDtoMapper pedidoDtoMapper;

    @GetMapping
    public ResponseEntity<List<PedidoResponse>> listarPedidos(@AuthenticationPrincipal Jwt jwt) {
        List<Pedido> pedidos = listarPedidosUseCase.execute(jwt.getSubject());
        return ResponseEntity.ok(pedidos.stream().map(pedidoDtoMapper::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PedidoResponse> obtenerPedido(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Pedido pedido = obtenerPedidoUseCase.execute(PedidoId.de(id), jwt.getSubject());
        return ResponseEntity.ok(pedidoDtoMapper.toResponse(pedido));
    }
}
