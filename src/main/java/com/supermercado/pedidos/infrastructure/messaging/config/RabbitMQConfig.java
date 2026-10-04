package com.supermercado.pedidos.infrastructure.messaging.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia RabbitMQ de pedidos-service (ver "Contrato de eventos" en el
 * ROADMAP: un exchange topic por servicio productor, routing key = eventType,
 * cada consumidor arma su propia cola con su propia dead-letter queue).
 *
 * pedidos-service es consumidor de carrito.events (exchange que declara
 * carrito-service como productor) y productor de pedidos.events. El exchange
 * de carrito.events se declara igual aqui, de forma defensiva e idempotente:
 * RabbitMQ no falla si dos servicios declaran el mismo exchange con las
 * mismas propiedades, y esto permite bindear la cola aunque carrito-service
 * todavia no haya arrancado.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_CARRITO_EVENTS = "carrito.events";
    public static final String EXCHANGE_PEDIDOS_EVENTS = "pedidos.events";

    public static final String ROUTING_KEY_CHECKOUT_INICIADO = "carrito.checkout-iniciado";
    public static final String ROUTING_KEY_PEDIDO_CREADO = "pedido.creado";

    public static final String QUEUE_CHECKOUT_INICIADO = "pedidos.carrito-checkout-iniciado";
    private static final String DLX_PEDIDOS = "pedidos.dlx";
    private static final String DLQ_CHECKOUT_INICIADO = "pedidos.carrito-checkout-iniciado.dlq";

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public TopicExchange carritoEventsExchange() {
        return new TopicExchange(EXCHANGE_CARRITO_EVENTS);
    }

    @Bean
    public TopicExchange pedidosEventsExchange() {
        return new TopicExchange(EXCHANGE_PEDIDOS_EVENTS);
    }

    @Bean
    public DirectExchange pedidosDeadLetterExchange() {
        return new DirectExchange(DLX_PEDIDOS);
    }

    @Bean
    public Queue checkoutIniciadoDeadLetterQueue() {
        return QueueBuilder.durable(DLQ_CHECKOUT_INICIADO).build();
    }

    @Bean
    public Binding checkoutIniciadoDeadLetterBinding() {
        return BindingBuilder.bind(checkoutIniciadoDeadLetterQueue())
                .to(pedidosDeadLetterExchange())
                .with(DLQ_CHECKOUT_INICIADO);
    }

    @Bean
    public Queue checkoutIniciadoQueue() {
        return QueueBuilder.durable(QUEUE_CHECKOUT_INICIADO)
                .withArgument("x-dead-letter-exchange", DLX_PEDIDOS)
                .withArgument("x-dead-letter-routing-key", DLQ_CHECKOUT_INICIADO)
                .build();
    }

    @Bean
    public Binding checkoutIniciadoBinding() {
        return BindingBuilder.bind(checkoutIniciadoQueue())
                .to(carritoEventsExchange())
                .with(ROUTING_KEY_CHECKOUT_INICIADO);
    }

    /**
     * Los reintentos son para fallos transitorios. No se reintenta un mensaje que
     * el listener ya rechazo de forma definitiva (AmqpRejectAndDontRequeueException,
     * a veces envuelta en otra excepcion) ni uno que Spring AMQP clasifica como
     * fatal (por ejemplo, JSON que no se puede leer): ambos van directo a la DLQ.
     */
    @Bean
    public RabbitListenerRetrySettingsCustomizer noReintentarRechazosDefinitivos() {
        return settings -> settings.setExceptionPredicate(error -> !esIrrecuperable(error));
    }

    /**
     * Recorre la cadena de causas: Spring envuelve el error real del listener en
     * otra excepcion. Los tipos de conversion/firma son los que Spring AMQP
     * considera fatales (reintentar no los arregla).
     */
    private static boolean esIrrecuperable(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause() == actual ? null : actual.getCause()) {
            if (actual instanceof AmqpRejectAndDontRequeueException
                    || actual instanceof MessageConversionException
                    || actual instanceof org.springframework.messaging.converter.MessageConversionException
                    || actual instanceof ClassCastException
                    || actual instanceof NoSuchMethodException) {
                return true;
            }
        }
        return false;
    }
}
