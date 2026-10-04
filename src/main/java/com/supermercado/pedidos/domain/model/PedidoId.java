package com.supermercado.pedidos.domain.model;

import java.util.Objects;
import java.util.UUID;

public final class PedidoId {

    private final UUID valor;

    private PedidoId(UUID valor) {
        this.valor = Objects.requireNonNull(valor, "El id de pedido no puede ser nulo");
    }

    public static PedidoId nuevo() {
        return new PedidoId(UUID.randomUUID());
    }

    public static PedidoId de(UUID valor) {
        return new PedidoId(valor);
    }

    public static PedidoId de(String valor) {
        Objects.requireNonNull(valor, "El id de pedido no puede ser nulo");
        return new PedidoId(UUID.fromString(valor));
    }

    public UUID getValor() {
        return valor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PedidoId that = (PedidoId) o;
        return Objects.equals(valor, that.valor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valor);
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
