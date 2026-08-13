package com.cafe.orderservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaErrorHandlingConfigTest {

    @Test
    void kafkaErrorHandler_buildsDefaultErrorHandlerWithDlqRecoverer() {
        KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);

        DefaultErrorHandler handler = new KafkaErrorHandlingConfig().kafkaErrorHandler(kafkaTemplate);

        assertThat(handler).isNotNull();
    }
}
