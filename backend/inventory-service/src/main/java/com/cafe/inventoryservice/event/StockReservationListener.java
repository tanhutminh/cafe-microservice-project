package com.cafe.inventoryservice.event;

import com.cafe.common.event.InventoryReserveStockCommand;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.inventoryservice.reservation.StockReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class StockReservationListener {

    private static final Logger log = LoggerFactory.getLogger(StockReservationListener.class);
    private static final String REPLY_TOPIC = "inventory.stock-reservation.reply";

    private final StockReservationService stockReservationService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public StockReservationListener(StockReservationService stockReservationService,
                                     KafkaTemplate<Object, Object> kafkaTemplate) {
        this.stockReservationService = stockReservationService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "inventory.reserve-stock.command")
    public void onReserveStockCommand(InventoryReserveStockCommand command,
                                       @Header(KafkaHeaders.CORRELATION_ID) String correlationId) {
        InventoryStockReservationReply reply =
                stockReservationService.reserve(command.orderId(), correlationId, command.items());

        Message<InventoryStockReservationReply> message = MessageBuilder.withPayload(reply)
                .setHeader(KafkaHeaders.TOPIC, REPLY_TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(command.orderId()))
                .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
                .build();
        kafkaTemplate.send(message);

        log.info("Inventory saga step: order {} correlation {} reservation success={}",
                command.orderId(), correlationId, reply.success());
    }
}
