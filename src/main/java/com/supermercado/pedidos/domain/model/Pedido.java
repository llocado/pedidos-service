package com.supermercado.pedidos.domain.model;

import com.supermercado.pedidos.domain.exception.PedidoSinItemsException;
import com.supermercado.pedidos.domain.exception.TransicionEstadoInvalidaException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Pedido {

    private final PedidoId id;
    private final UUID carritoIdOrigen;
    private final String usuarioId;
    private final List<ItemPedido> items;
    private final BigDecimal total;
    private EstadoPedido estado;
    private final Instant creadoEn;
    private Instant actualizadoEn;

    private Pedido(PedidoId id, UUID carritoIdOrigen, String usuarioId, List<ItemPedido> items,
                    BigDecimal total, EstadoPedido estado, Instant creadoEn, Instant actualizadoEn) {
        this.id = id;
        this.carritoIdOrigen = carritoIdOrigen;
        this.usuarioId = usuarioId;
        this.items = List.copyOf(items);
        this.total = total;
        this.estado = estado;
        this.creadoEn = creadoEn;
        this.actualizadoEn = actualizadoEn;
    }

    public static Pedido crearDesdeCarrito(UUID carritoIdOrigen, String usuarioId, List<ItemPedido> items) {
        Objects.requireNonNull(carritoIdOrigen, "El id del carrito de origen no puede ser nulo");
        validarUsuarioId(usuarioId);
        if (items == null || items.isEmpty()) {
            throw new PedidoSinItemsException("No se puede crear un pedido sin items");
        }
        BigDecimal total = items.stream()
                .map(ItemPedido::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant ahora = Instant.now();
        return new Pedido(PedidoId.nuevo(), carritoIdOrigen, usuarioId, items, total, EstadoPedido.CREADO, ahora, ahora);
    }

    public static Pedido reconstruir(PedidoId id, UUID carritoIdOrigen, String usuarioId, List<ItemPedido> items,
                                      BigDecimal total, EstadoPedido estado, Instant creadoEn, Instant actualizadoEn) {
        return new Pedido(
                Objects.requireNonNull(id, "El id no puede ser nulo"),
                Objects.requireNonNull(carritoIdOrigen, "El id del carrito de origen no puede ser nulo"),
                usuarioId,
                Objects.requireNonNull(items, "Los items no pueden ser nulos"),
                Objects.requireNonNull(total, "El total no puede ser nulo"),
                Objects.requireNonNull(estado, "El estado no puede ser nulo"),
                Objects.requireNonNull(creadoEn, "creadoEn no puede ser nulo"),
                Objects.requireNonNull(actualizadoEn, "actualizadoEn no puede ser nulo")
        );
    }

    public void transicionarA(EstadoPedido siguiente) {
        if (!estado.puedeTransicionarA(siguiente)) {
            throw new TransicionEstadoInvalidaException(
                    "No se puede pasar de " + estado + " a " + siguiente);
        }
        this.estado = siguiente;
        this.actualizadoEn = Instant.now();
    }

    private static void validarUsuarioId(String usuarioId) {
        Objects.requireNonNull(usuarioId, "El id de usuario no puede ser nulo");
        if (usuarioId.isBlank()) {
            throw new IllegalArgumentException("El id de usuario no puede estar vacio");
        }
    }

    public PedidoId getId() {
        return id;
    }

    public UUID getCarritoIdOrigen() {
        return carritoIdOrigen;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public List<ItemPedido> getItems() {
        return items;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
