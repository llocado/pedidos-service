package com.supermercado.pedidos.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "items_pedido")
@Getter
@Setter
@NoArgsConstructor
public class ItemPedidoEntity {

    // Id tecnico de la fila -- no forma parte del dominio (ItemPedido no
    // tiene identidad propia, su clave conceptual es productoId).
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "pedido_id", nullable = false)
    private PedidoEntity pedido;

    @Column(name = "producto_id", nullable = false)
    private UUID productoId;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(name = "precio_unitario", nullable = false, precision = 12, scale = 2)
    private BigDecimal precioUnitario;

    @Column(nullable = false, length = 3)
    private String moneda;

    @Column(nullable = false)
    private Integer cantidad;
}
