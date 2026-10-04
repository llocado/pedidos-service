package com.supermercado.pedidos.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Linea de un pedido. Es una "foto" del item de carrito al momento del
 * checkout -- pedidos-service no importa el dominio de carrito-service ni de
 * productos-service (cada servicio dueno del suyo), asi que esta es su
 * propia representacion, poblada desde el evento carrito.checkout-iniciado.
 */
public final class ItemPedido {

    private final UUID productoId;
    private final String nombre;
    private final BigDecimal precioUnitario;
    private final String moneda;
    private final int cantidad;

    private ItemPedido(UUID productoId, String nombre, BigDecimal precioUnitario, String moneda, int cantidad) {
        this.productoId = Objects.requireNonNull(productoId, "El id de producto no puede ser nulo");
        this.nombre = Objects.requireNonNull(nombre, "El nombre no puede ser nulo");
        this.precioUnitario = Objects.requireNonNull(precioUnitario, "El precio unitario no puede ser nulo");
        this.moneda = Objects.requireNonNull(moneda, "La moneda no puede ser nula");
        validarCantidad(cantidad);
        this.cantidad = cantidad;
    }

    public static ItemPedido de(UUID productoId, String nombre, BigDecimal precioUnitario, String moneda, int cantidad) {
        return new ItemPedido(productoId, nombre, precioUnitario, moneda, cantidad);
    }

    public BigDecimal getSubtotal() {
        return precioUnitario.multiply(BigDecimal.valueOf(cantidad));
    }

    private static void validarCantidad(int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a 0");
        }
    }

    public UUID getProductoId() {
        return productoId;
    }

    public String getNombre() {
        return nombre;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public String getMoneda() {
        return moneda;
    }

    public int getCantidad() {
        return cantidad;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ItemPedido that = (ItemPedido) o;
        return Objects.equals(productoId, that.productoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productoId);
    }

    @Override
    public String toString() {
        return "ItemPedido{productoId=" + productoId + ", cantidad=" + cantidad + "}";
    }
}
