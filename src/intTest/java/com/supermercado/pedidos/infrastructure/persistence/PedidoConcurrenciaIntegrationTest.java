package com.supermercado.pedidos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supermercado.pedidos.application.port.PedidoRepositoryPort;
import com.supermercado.pedidos.domain.model.EstadoPedido;
import com.supermercado.pedidos.domain.model.ItemPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.domain.model.PedidoId;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dos transacciones reales sobre el mismo pedido (sin @Transactional en la clase: cada TransactionTemplate abre y
 * confirma la suya). Reproduce la carrera real: pago.aprobado (CREADO a PAGADO) y stock.descuento-rechazado (a
 * SIN_STOCK) se procesan a la vez y ambos leen CREADO. Sin @Version el ultimo en escribir pisaba al otro.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.port=1"})
class PedidoConcurrenciaIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PedidoRepositoryPort pedidoRepositoryPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void elSegundoEnConfirmarFalla_YNoPisaElEstadoDelPrimero() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PedidoId id = tx.execute(estado -> pedidoRepositoryPort.guardar(Pedido.crearDesdeCarrito(UUID.randomUUID(), "usuario-conc",
                List.of(ItemPedido.de(UUID.randomUUID(), "Manzana", new BigDecimal("1500"), "CLP", 2)))).getId());

        assertThatThrownBy(() -> tx.executeWithoutResult(estadoA -> {
            // A (pago.aprobado) lee el pedido en CREADO...
            Pedido leidoPorA = pedidoRepositoryPort.buscarPorId(id).orElseThrow();

            // ...y mientras tanto B (stock.descuento-rechazado) lo pasa a SIN_STOCK y confirma.
            TransactionTemplate txB = new TransactionTemplate(transactionManager);
            txB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txB.executeWithoutResult(estadoB -> {
                Pedido leidoPorB = pedidoRepositoryPort.buscarPorId(id).orElseThrow();
                leidoPorB.transicionarA(EstadoPedido.SIN_STOCK);
                pedidoRepositoryPort.guardar(leidoPorB);
            });

            // A pasa a PAGADO sobre su lectura vieja: no debe pisar el SIN_STOCK de B.
            leidoPorA.transicionarA(EstadoPedido.PAGADO);
            pedidoRepositoryPort.guardar(leidoPorA);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(pedidoRepositoryPort.buscarPorId(id).orElseThrow().getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
    }

    @Test
    void sinConcurrencia_LasEscriturasSucesivasFuncionanNormalmente() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PedidoId id = tx.execute(estado -> pedidoRepositoryPort.guardar(Pedido.crearDesdeCarrito(UUID.randomUUID(), "usuario-sec",
                List.of(ItemPedido.de(UUID.randomUUID(), "Pera", new BigDecimal("900"), "CLP", 1)))).getId());

        for (EstadoPedido siguiente : List.of(EstadoPedido.PAGADO, EstadoPedido.SIN_STOCK)) {
            tx.executeWithoutResult(estado -> {
                Pedido pedido = pedidoRepositoryPort.buscarPorId(id).orElseThrow();
                pedido.transicionarA(siguiente);
                pedidoRepositoryPort.guardar(pedido);
            });
        }

        assertThat(pedidoRepositoryPort.buscarPorId(id).orElseThrow().getEstado()).isEqualTo(EstadoPedido.SIN_STOCK);
    }
}
