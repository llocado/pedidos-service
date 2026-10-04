package com.supermercado.pedidos.infrastructure.messaging.listener;

import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase;
import com.supermercado.pedidos.application.usecase.CrearPedidoDesdeCarritoUseCase.ItemCarritoComando;
import com.supermercado.pedidos.infrastructure.messaging.config.RabbitMQConfig;
import com.supermercado.pedidos.infrastructure.messaging.dto.CarritoCheckoutIniciadoPayload;
import com.supermercado.pedidos.infrastructure.messaging.dto.EventEnvelope;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CarritoEventListener {

    private final CrearPedidoDesdeCarritoUseCase crearPedidoDesdeCarritoUseCase;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CHECKOUT_INICIADO)
    public void escucharCheckoutIniciado(EventEnvelope envelope) {
        CarritoCheckoutIniciadoPayload payload =
                objectMapper.treeToValue(envelope.payload(), CarritoCheckoutIniciadoPayload.class);

        List<ItemCarritoComando> items = payload.items().stream()
                .map(item -> new ItemCarritoComando(
                        item.productoId(), item.nombre(), item.precioUnitario(), item.moneda(), item.cantidad()))
                .toList();

        crearPedidoDesdeCarritoUseCase.execute(envelope.eventId(), payload.carritoId(), payload.usuarioId(), items);
    }
}
