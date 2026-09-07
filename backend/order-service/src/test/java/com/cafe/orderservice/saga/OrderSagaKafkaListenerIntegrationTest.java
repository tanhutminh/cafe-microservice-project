package com.cafe.orderservice.saga;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderService;
import com.cafe.orderservice.outbox.OutboxMessageRepository;
import com.cafe.orderservice.outbox.OutboxMessageType;
import com.cafe.orderservice.table.DiningTable;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real Kafka broker (not a mocked {@code KafkaTemplate}, not a direct Java call to
 * the listener method), since {@link OrderSaga#onStockReservationReply} and {@link
 * OrderSaga#onStockCommitReply}'s entire job is real Kafka wiring - JSON deserialization through
 * the actual {@code ErrorHandlingDeserializer}/{@code JsonDeserializer} stack,
 * {@code @Header(KafkaHeaders.CORRELATION_ID)} extraction, and topic-to-method dispatch - none of
 * which a mock can exercise. This is a full {@link SpringBootTest} (not a narrower slice)
 * specifically so the real {@code application.yml} Kafka autoconfiguration and {@code
 * KafkaErrorHandlingConfig} are the ones under test, not a hand-assembled substitute. The Kafka
 * image ({@code apache/kafka:4.3.1}) matches {@code docker-compose.yml}'s real deployment image
 * exactly, not just the version.
 *
 * <p>{@code OrderService}, {@code OrderSagaStateService}, and {@code OutboxMessageRepository} are
 * mocked: {@code OrderSaga}'s own state-transition decision logic (which branch to take, what to
 * call) already has dedicated, thorough coverage in {@code OrderSagaTest} - this class exists to
 * prove the listener methods actually get invoked with a correctly-deserialized payload and header
 * when a real message arrives, not to re-verify a decision {@code OrderSaga} makes internally.
 * {@code app.outbox.poll-enabled=false} keeps {@code OutboxPoller}'s bean out of this context
 * entirely, so its scheduled relay can never register a stray interaction on the mocked {@code
 * OutboxMessageRepository} while a test is asserting against it.
 *
 * <p>Every test's {@code assertAll} leads with a {@code timeout(...)}-based wait on {@code
 * sagaStateService.shouldIgnoreReply(...)}, the one call both listener methods make unconditionally
 * before any branch-specific behavior - necessary since a real Kafka message is consumed
 * asynchronously relative to the test thread.
 *
 * <p>That leading verify only proves *that one call* already happened by the time it returns. It
 * says nothing about whether the rest of the listener method - still running independently on the
 * Kafka consumer thread - has also finished. So every later *positive* assertion (a mocked call
 * that genuinely does happen on that branch) still carries its own {@code timeout(TIMEOUT_MS)}.
 *
 * <p>The "ignored" branch is the one case where this doesn't apply. Once {@code shouldIgnoreReply}
 * is stubbed {@code true}, the only remaining code is a log statement and an early return, so no
 * other mocked collaborator can *ever* be reached on that path, at any point in time - exactly
 * determinative, not just fast enough in practice.
 *
 * <p>The same reasoning is why the negative assertions below (both here and on the "ignored"
 * branch) never carry a {@code timeout(...)}. {@code verifyNoInteractions} on a path that
 * structurally never touches that mock holds regardless of how long you wait, so checking
 * immediately is correct, not just convenient. ({@code verifyNoInteractions}/{@code
 * verifyNoMoreInteractions} also don't accept a {@code VerificationMode} to begin with.)
 *
 * <p>A full {@link SpringBootTest} boots the whole application context, including JPA/Flyway for
 * every OTHER repository in the module (not just the three mocked here) - {@code application.yml}'s
 * {@code ddl-auto: validate} and {@code spring.flyway.enabled: true} mean a real, reachable
 * Postgres is genuinely required just for the context to start, independent of whatever this test
 * itself exercises. This intentionally runs its own Postgres container rather than reusing {@code
 * AbstractPostgresRepositoryTest}'s: that class is annotated for a narrow {@code @DataJpaTest}
 * slice, which this full-context test can't extend, and unlike that class's three
 * {@code @DataJpaTest} subclasses (which share one warm, byte-identical Spring context), this
 * test's {@code @SpringBootTest} context is never reused there regardless - sharing only the
 * container process would save little and would cost a coupling between two independently-evolving
 * test concerns for a small win.
 *
 * <p>Publishes onto the reply topics this class listens to via the SAME autoconfigured {@code
 * KafkaTemplate<Object, Object>} bean production code uses ({@link
 * com.cafe.orderservice.outbox.OutboxMessagePublisher}, {@code
 * StockReservationListener#resendReplyIfProcessed} in inventory-service) - not a hand-rolled
 * producer with separately-configured serializers, which would risk testing a shortcut instead of
 * the real serde configuration.
 *
 * <p>Deliberately does not exercise a validation-failure/malformed-payload scenario: {@code
 * KafkaErrorHandlingConfig}'s real {@code DefaultErrorHandler} (dead-letter recoverer plus an
 * exponential backoff with a 10-second max elapsed time) is genuinely live in this context, and
 * deliberately routing a message through it here would risk a slow/flaky test for a path already
 * covered at the unit level by {@code OrderSagaTest}'s invalid-payload cases - the DLQ/backoff
 * behavior itself is a distinct concern this class deliberately leaves untested.
 */
@Tag("testcontainers")
@SpringBootTest
@TestPropertySource(properties = "app.outbox.poll-enabled=false")
@Testcontainers
class OrderSagaKafkaListenerIntegrationTest {

  private static final long TIMEOUT_MS = 10_000L;

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

  @Container @ServiceConnection
  static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");

  @Autowired private KafkaTemplate<Object, Object> kafkaTemplate;

  @MockitoBean private OrderService orderService;
  @MockitoBean private OrderSagaStateService sagaStateService;
  @MockitoBean private OutboxMessageRepository outboxMessageRepository;

  private static long nextOrderId = 1;

  private long orderId;
  private String correlationId;

  @BeforeEach
  void setUp() {
    orderId = nextOrderId++;
    correlationId = "corr-" + orderId;
    when(sagaStateService.shouldIgnoreReply(orderId, correlationId)).thenReturn(false);
  }

  /**
   * A minimal, never-persisted {@code Order} standing in for {@code OrderService.markPaid}'s return
   * value - {@code onStockCommitReply}'s success branch dereferences it (table id, items, closedAt,
   * paymentMethod) to build the {@code OrderPaidEvent} it enqueues.
   */
  private Order paidOrder() {
    return Order.builder()
        .id(orderId)
        .table(DiningTable.builder().id(1L).build())
        .paymentMethod("CASH")
        .closedAt(Instant.now())
        .build();
  }

  private void send(String topic, Object payload) {
    Message<Object> message =
        MessageBuilder.withPayload(payload)
            .setHeader(KafkaHeaders.TOPIC, topic)
            .setHeader(KafkaHeaders.KEY, String.valueOf(orderId))
            .setHeader(KafkaHeaders.CORRELATION_ID, correlationId)
            .build();
    kafkaTemplate.send(message);
  }

  @Test
  void onStockReservationReply_success_marksOrderAndSagaConfirmed() {
    send(OrderSaga.STOCK_RESERVATION_REPLY_TOPIC, InventoryStockReservationReply.success(orderId));

    assertAll(
        () ->
            verify(sagaStateService, timeout(TIMEOUT_MS)).shouldIgnoreReply(orderId, correlationId),
        () -> verify(orderService, timeout(TIMEOUT_MS)).markConfirmed(orderId),
        () -> verify(sagaStateService, timeout(TIMEOUT_MS)).markConfirmed(orderId),
        () -> verifyNoInteractions(outboxMessageRepository));
  }

  @Test
  void onStockReservationReply_failure_compensatesToOpen() {
    String reason = "insufficient stock";

    send(
        OrderSaga.STOCK_RESERVATION_REPLY_TOPIC,
        InventoryStockReservationReply.failure(orderId, reason));

    assertAll(
        () ->
            verify(sagaStateService, timeout(TIMEOUT_MS)).shouldIgnoreReply(orderId, correlationId),
        () -> verify(orderService, timeout(TIMEOUT_MS)).compensateToOpen(orderId, reason),
        () -> verify(sagaStateService, timeout(TIMEOUT_MS)).markCompensated(orderId),
        () -> verifyNoInteractions(outboxMessageRepository));
  }

  @Test
  void onStockCommitReply_success_marksPaidAndQueuesOrderPaidOutboxRow() {
    when(orderService.markPaid(orderId)).thenReturn(paidOrder());

    send(OrderSaga.STOCK_COMMIT_REPLY_TOPIC, InventoryStockCommitReply.success(orderId));

    assertAll(
        () ->
            verify(sagaStateService, timeout(TIMEOUT_MS)).shouldIgnoreReply(orderId, correlationId),
        () -> verify(orderService, timeout(TIMEOUT_MS)).markPaid(orderId),
        () -> verify(sagaStateService, timeout(TIMEOUT_MS)).markCompleted(orderId),
        () ->
            verify(outboxMessageRepository, timeout(TIMEOUT_MS))
                .save(
                    argThat(
                        message ->
                            message.getMessageType() == OutboxMessageType.ORDER_PAID
                                && message.getOrderId().equals(orderId)
                                && message.getCorrelationId().equals(correlationId))));
  }

  @Test
  void onStockCommitReply_failure_revertsToConfirmed() {
    String reason = "card declined";

    send(OrderSaga.STOCK_COMMIT_REPLY_TOPIC, InventoryStockCommitReply.failure(orderId, reason));

    assertAll(
        () ->
            verify(sagaStateService, timeout(TIMEOUT_MS)).shouldIgnoreReply(orderId, correlationId),
        () -> verify(orderService, timeout(TIMEOUT_MS)).revertToConfirmed(orderId, reason),
        () -> verify(sagaStateService, timeout(TIMEOUT_MS)).markConfirmed(orderId),
        () -> verifyNoInteractions(outboxMessageRepository));
  }

  private static Stream<Arguments> ignoredReplyScenarios() {
    return Stream.of(
        Arguments.of(
            "onStockReservationReply_staleOrSettled_isIgnored",
            OrderSaga.STOCK_RESERVATION_REPLY_TOPIC,
            (Function<Long, Object>) InventoryStockReservationReply::success),
        Arguments.of(
            "onStockCommitReply_staleOrSettled_isIgnored",
            OrderSaga.STOCK_COMMIT_REPLY_TOPIC,
            (Function<Long, Object>) InventoryStockCommitReply::success));
  }

  /**
   * Both listener methods, once {@code shouldIgnoreReply} answers true, touch {@code
   * sagaStateService} exactly once (the guard call itself) and neither {@code orderService} nor
   * {@code outboxMessageRepository} at all - an identical postcondition regardless of which
   * listener/payload fired, so one parameterized case covers both.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("ignoredReplyScenarios")
  void onReply_staleOrSettled_isIgnored(
      String caseName, String topic, Function<Long, Object> payloadFactory) {
    when(sagaStateService.shouldIgnoreReply(orderId, correlationId)).thenReturn(true);

    send(topic, payloadFactory.apply(orderId));

    assertAll(
        () ->
            verify(sagaStateService, timeout(TIMEOUT_MS)).shouldIgnoreReply(orderId, correlationId),
        () -> verifyNoMoreInteractions(sagaStateService),
        () -> verifyNoInteractions(orderService),
        () -> verifyNoInteractions(outboxMessageRepository));
  }
}
