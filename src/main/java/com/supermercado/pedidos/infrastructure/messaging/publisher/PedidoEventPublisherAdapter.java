package com.supermercado.pedidos.infrastructure.messaging.publisher;

import com.supermercado.pedidos.application.port.PedidoEventPublisherPort;
import com.supermercado.pedidos.domain.model.ItemPedido;
import com.supermercado.pedidos.domain.model.Pedido;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import com.supermercado.pedidos.infrastructure.messaging.dto.PedidoCreadoPayload;
import com.supermercado.pedidos.infrastructure.messaging.dto.PedidoCreadoPayload.ItemEventoDto;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PedidoEventPublisherAdapter implements PedidoEventPublisherPort {

    private static final String PRODUCER = "pedidos-service";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publicarPedidoCreado(Pedido pedido) {
        PedidoCreadoPayload payload = new PedidoCreadoPayload(
                pedido.getId().getValor(),
                pedido.getCarritoIdOrigen(),
                pedido.getUsuarioId(),
                pedido.getItems().stream().map(this::toEventoDto).toList(),
                pedido.getTotal()
        );

        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO,
                1,
                Instant.now(),
                PRODUCER,
                null,
                objectMapper.valueToTree(payload)
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_PEDIDOS_EVENTS, RabbitMQConfig.ROUTING_KEY_PEDIDO_CREADO, envelope);
    }

    private ItemEventoDto toEventoDto(ItemPedido item) {
        return new ItemEventoDto(
                item.getProductoId(), item.getNombre(), item.getPrecioUnitario(), item.getMoneda(), item.getCantidad());
    }
}
