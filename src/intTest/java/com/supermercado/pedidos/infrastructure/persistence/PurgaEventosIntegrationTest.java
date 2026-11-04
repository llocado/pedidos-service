package com.supermercado.pedidos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.supermercado.pedidos.application.usecase.PurgarEventosAntiguosUseCase;
import com.supermercado.pedidos.infrastructure.persistence.entity.EventoProcesadoEntity;
import com.supermercado.pedidos.infrastructure.persistence.repository.SpringDataEventoProcesadoRepository;
import com.supermercado.pedidos.infrastructure.scheduling.PurgaEventosJob;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Retencion contra un Postgres real: lo viejo se borra y lo reciente se conserva.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "spring.rabbitmq.port=1"})
class PurgaEventosIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private PurgarEventosAntiguosUseCase purgarEventosAntiguosUseCase;
    @Autowired
    private PurgaEventosJob purgaEventosJob;
    @Autowired
    private SpringDataEventoProcesadoRepository eventoProcesadoRepository;

    @BeforeEach
    void limpiar() {
        eventoProcesadoRepository.deleteAll();
    }

    private UUID procesado(Instant cuando) {
        EventoProcesadoEntity e = new EventoProcesadoEntity();
        e.setEventId(UUID.randomUUID());
        e.setProcesadoEn(cuando);
        return eventoProcesadoRepository.save(e).getEventId();
    }

    private static Instant hace(long dias) {
        return Instant.now().minus(dias, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    void eventosProcesados_SeBorraLoAnteriorAlPlazo_YSeConservaLoReciente() {
        UUID viejo = procesado(hace(31));
        UUID justoDentro = procesado(hace(29));
        UUID reciente = procesado(Instant.now());

        var resultado = purgarEventosAntiguosUseCase.execute(Duration.ofDays(30), 1000);

        assertThat(resultado.procesados()).isEqualTo(1);
        assertThat(eventoProcesadoRepository.existsById(viejo)).isFalse();
        assertThat(eventoProcesadoRepository.existsById(justoDentro)).isTrue();
        assertThat(eventoProcesadoRepository.existsById(reciente)).isTrue();
    }

    @Test
    void conMasFilasQueElLote_BorraEnVariasPasadas() {
        for (int i = 0; i < 25; i++) {
            procesado(hace(60));
        }
        UUID reciente = procesado(Instant.now());

        var resultado = purgarEventosAntiguosUseCase.execute(Duration.ofDays(30), 10);

        assertThat(resultado.procesados()).isEqualTo(25);
        assertThat(eventoProcesadoRepository.count()).isEqualTo(1);
        assertThat(eventoProcesadoRepository.existsById(reciente)).isTrue();
    }

    @Test
    void elJobProgramado_UsaLosPlazosConfigurados() {
        UUID viejo = procesado(hace(31));
        UUID reciente = procesado(hace(1));

        purgaEventosJob.purgar();

        assertThat(eventoProcesadoRepository.existsById(viejo)).isFalse();
        assertThat(eventoProcesadoRepository.existsById(reciente)).isTrue();
    }
}
