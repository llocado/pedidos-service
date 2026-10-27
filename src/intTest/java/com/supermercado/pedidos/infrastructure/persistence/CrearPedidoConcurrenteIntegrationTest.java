package com.supermercado.pedidos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.supermercado.pedidos.application.port.PedidoEventPublisherPort;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase.ItemCarritoComando;
import com.supermercado.pedidos.domain.model.Pedido;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Varias entregas simultaneas del mismo carrito.checkout-iniciado (varias instancias o consumidores concurrentes),
 * con transacciones reales y sin @Transactional en la clase. Todas pasan el chequeo "ya procesado" a la vez: solo una
 * puede crear el pedido y publicar pedido.creado; las demas deben chocar con la unica de eventos_procesados SIN haber
 * publicado nada (antes se reservaba el eventId al final y salian pedido.creado de pedidos que se revertian).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.port=1"})
class CrearPedidoConcurrenteIntegrationTest {

    private static final int ENTREGAS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CrearPedidoDesdeCarritoUseCase crearPedidoDesdeCarritoUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PedidoEventPublisherPort pedidoEventPublisherPort;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void entregasSimultaneasDelMismoEvento_DeberianCrearUnSoloPedido_YPublicarUnSoloEvento() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID carritoId = UUID.randomUUID();
        List<ItemCarritoComando> items = List.of(
                new ItemCarritoComando(UUID.randomUUID(), "Manzana", new BigDecimal("1500"), "CLP", 2));
        CyclicBarrier salida = new CyclicBarrier(ENTREGAS);

        ExecutorService hilos = Executors.newFixedThreadPool(ENTREGAS);
        List<Future<Throwable>> resultados = new ArrayList<>();
        for (int i = 0; i < ENTREGAS; i++) {
            resultados.add(hilos.submit(() -> {
                salida.await();
                try {
                    crearPedidoDesdeCarritoUseCase.execute(eventId, UUID.randomUUID(), carritoId, "usuario-conc", items);
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            }));
        }
        List<Throwable> errores = new ArrayList<>();
        for (Future<Throwable> resultado : resultados) {
            Throwable error = resultado.get();
            if (error != null) {
                errores.add(error);
            }
        }
        hilos.shutdown();

        // Las que pierden la carrera solo pueden fallar por la unica; las que llegan tarde ven "ya procesado" y salen sin error.
        assertThat(errores).allSatisfy(e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class));
        Integer pedidos = jdbcTemplate.queryForObject("select count(*) from pedidos where carrito_id_origen = ?", Integer.class, carritoId);
        assertThat(pedidos).isEqualTo(1);
        verify(pedidoEventPublisherPort, times(1)).publicarPedidoCreado(any(Pedido.class), any());
    }

    @Test
    void unNombreQueNoCabeEnLaColumna_DeberiaFallarAntesDePublicarPedidoCreado() {
        UUID carritoId = UUID.randomUUID();
        // items_pedido.nombre es varchar(150): con 200 caracteres el INSERT falla. Si el INSERT se difiriera al commit, el evento ya habria salido.
        List<ItemCarritoComando> items = List.of(
                new ItemCarritoComando(UUID.randomUUID(), "n".repeat(200), new BigDecimal("1500"), "CLP", 1));

        assertThatThrownBy(() -> crearPedidoDesdeCarritoUseCase.execute(UUID.randomUUID(), UUID.randomUUID(), carritoId, "usuario-largo", items))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(pedidoEventPublisherPort, never()).publicarPedidoCreado(any(Pedido.class), any());
        Integer pedidos = jdbcTemplate.queryForObject("select count(*) from pedidos where carrito_id_origen = ?", Integer.class, carritoId);
        assertThat(pedidos).isZero();
    }
}
