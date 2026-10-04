package com.supermercado.pedidos.infrastructure.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.retry.RetryPolicySettings;

class RabbitMQConfigTest {

    private Predicate<Throwable> seReintenta;

    @BeforeEach
    void setUp() {
        RetryPolicySettings settings = new RetryPolicySettings();
        new RabbitMQConfig().noReintentarRechazosDefinitivos().customize(settings);
        seReintenta = settings.getExceptionPredicate();
    }

    @Test
    void deberiaReintentar_UnFalloTransitorio_AunqueVengaEnvuelto() {
        assertThat(seReintenta.test(new IllegalStateException("base de datos no disponible"))).isTrue();
        assertThat(seReintenta.test(new RuntimeException("falla", new IllegalStateException("db")))).isTrue();
    }

    @Test
    void noDeberiaReintentar_UnRechazoDefinitivo_AunqueVengaEnvuelto() {
        AmqpRejectAndDontRequeueException rechazo = new AmqpRejectAndDontRequeueException("payload invalido");

        assertThat(seReintenta.test(rechazo)).isFalse();
        assertThat(seReintenta.test(new RuntimeException("falla", rechazo))).isFalse();
    }

    @Test
    void noDeberiaReintentar_UnMensajeQueNoSePuedeConvertir() {
        MessageConversionException noConvertible = new MessageConversionException("json roto");

        assertThat(seReintenta.test(new RuntimeException("falla", noConvertible))).isFalse();
    }
}
