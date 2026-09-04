package com.cafe.orderservice.saga;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.testsupport.AbstractPostgresRepositoryTest;
import com.cafe.orderservice.testsupport.RepositoryTestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * {@link OrderSagaReconciliationJob#sweep}'s entire "find sagas stuck longer than the threshold"
 * guarantee depends on {@link OrderSagaStateRepository#findAllByStepInAndUpdatedAtBefore}'s query
 * text - a Mockito-mocked repository (this codebase's usual test style) can only prove the method
 * was called, never that it correctly filters by step AND by a real, persisted {@code updated_at}.
 *
 * <p>{@link OrderSaga} is mocked rather than real: its own retry-vs-compensate decision logic
 * ({@code retryOrCompensate}) already has dedicated coverage in {@code OrderSagaTest} - this class
 * exists to prove {@code sweep()}'s own responsibility only (finding the right candidates and
 * isolating one candidate's failure from the rest), not to re-verify a decision {@code OrderSaga}
 * makes internally. {@link OrderSagaReconciliationJob} itself has no {@code @Transactional}/AOP
 * behavior of its own, so it's constructed directly rather than obtained through Spring.
 */
class OrderSagaReconciliationJobTest extends AbstractPostgresRepositoryTest {

  private static final Duration STUCK_THRESHOLD = Duration.ofSeconds(60);

  @Autowired private TestEntityManager entityManager;
  @Autowired private OrderSagaStateRepository sagaStateRepository;

  private OrderSaga orderSaga;
  private OrderSagaReconciliationJob job;

  @BeforeEach
  void setUp() {
    orderSaga = mock(OrderSaga.class);
    job =
        new OrderSagaReconciliationJob(
            sagaStateRepository, orderSaga, new SagaReconciliationProperties(STUCK_THRESHOLD, 3));
  }

  /**
   * Only exists to satisfy {@code order_saga_state.order_id}'s FK to {@code orders.id} - the
   * order's own fields are irrelevant to every test below.
   */
  private Long newOrderId() {
    DiningTable table =
        RepositoryTestFixtures.occupiedTable(
            entityManager, "T-" + UUID.randomUUID().toString().substring(0, 8));
    return RepositoryTestFixtures.order(entityManager, table, OrderStatus.OPEN, Instant.now(), null)
        .getId();
  }

  private Long freshSaga(SagaStep step) {
    Long orderId = newOrderId();
    entityManager.persistFlushFind(
        OrderSagaState.builder()
            .orderId(orderId)
            .correlationId(UUID.randomUUID().toString())
            .step(step)
            .retryCount(0)
            .build());
    return orderId;
  }

  /**
   * Persists a saga in {@code step}, then backdates {@code updated_at} past {@link
   * #STUCK_THRESHOLD} via a bulk JPQL update issued directly through the entity manager, followed
   * by an explicit {@code clear()} - {@code OrderSagaState}'s own {@code @PreUpdate} callback would
   * silently reset a normal setter-then-flush write straight back to "now", and a bulk update
   * that's never cleared would leave the just-persisted (now-stale) instance sitting in this
   * session's first-level cache for anything read afterward.
   */
  private Long stuckSaga(SagaStep step) {
    Long orderId = freshSaga(step);
    entityManager
        .getEntityManager()
        .createQuery(
            "UPDATE OrderSagaState s SET s.updatedAt = :updatedAt WHERE s.orderId = :orderId")
        .setParameter("updatedAt", Instant.now().minus(STUCK_THRESHOLD).minusSeconds(30))
        .setParameter("orderId", orderId)
        .executeUpdate();
    entityManager.getEntityManager().clear();
    return orderId;
  }

  @ParameterizedTest
  @EnumSource(
      value = SagaStep.class,
      names = {"STOCK_RESERVATION_REQUESTED", "PAYMENT_REQUESTED"})
  void sweep_callsRetryOrCompensate_forStuckSaga(SagaStep step) {
    Long orderId = stuckSaga(step);

    job.sweep();

    verify(orderSaga).retryOrCompensate(orderId);
  }

  @ParameterizedTest
  @EnumSource(
      value = SagaStep.class,
      names = {"STOCK_RESERVATION_REQUESTED", "PAYMENT_REQUESTED"},
      mode = EnumSource.Mode.EXCLUDE)
  void sweep_ignoresSagaInADifferentStep_evenWhenUpdatedAtIsOld(SagaStep step) {
    stuckSaga(step);

    job.sweep();

    verifyNoInteractions(orderSaga);
  }

  @Test
  void sweep_ignoresStuckStepSaga_whenUpdatedAtIsRecent() {
    freshSaga(SagaStep.STOCK_RESERVATION_REQUESTED);

    job.sweep();

    verifyNoInteractions(orderSaga);
  }

  /**
   * The class's own core promise: one saga's {@code retryOrCompensate} failure must not stop the
   * sweep from reaching the rest. Verifies by orderId, not list position - {@code
   * findAllByStepInAndUpdatedAtBefore} has no {@code ORDER BY}, so Postgres's return order for
   * equally-matching rows isn't guaranteed.
   */
  @Test
  void sweep_processesEveryRemainingSaga_evenWhenOneThrows() {
    Long first = stuckSaga(SagaStep.STOCK_RESERVATION_REQUESTED);
    Long throwing = stuckSaga(SagaStep.STOCK_RESERVATION_REQUESTED);
    Long last = stuckSaga(SagaStep.PAYMENT_REQUESTED);
    doThrow(new RuntimeException("boom")).when(orderSaga).retryOrCompensate(throwing);

    job.sweep();

    assertAll(
        () -> verify(orderSaga).retryOrCompensate(first),
        () -> verify(orderSaga).retryOrCompensate(throwing),
        () -> verify(orderSaga).retryOrCompensate(last));
  }

  @Test
  void sweep_doesNothing_whenNoStuckSagasExist() {
    job.sweep();

    verifyNoInteractions(orderSaga);
  }
}
