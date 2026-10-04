package com.supermercado.pedidos.infrastructure.messaging.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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
}
