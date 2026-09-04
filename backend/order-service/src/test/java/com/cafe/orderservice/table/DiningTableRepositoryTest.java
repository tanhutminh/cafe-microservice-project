package com.cafe.orderservice.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.testsupport.AbstractPostgresRepositoryTest;
import com.cafe.orderservice.testsupport.RepositoryTestFixtures;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * {@link DiningTableRepository#occupyIfAvailable} and {@link
 * DiningTableRepository#releaseIfAllOrdersClosed} are both conditional {@code @Modifying}
 * single-statement updates whose correctness lives entirely in their JPQL text (a {@code
 * WHERE}-clause guard, and a {@code NOT EXISTS} subquery respectively) - a Mockito-mocked
 * repository can only prove the method was called, never what the query text itself actually
 * matches or writes.
 *
 * <p>Neither method is covered by a concurrency test here, unlike {@code
 * IngredientRepositoryTest}'s real 2-thread proof of its {@code @Lock(PESSIMISTIC_WRITE)} query.
 * These two methods carry no {@code @Lock} annotation at all - each is a single conditional SQL
 * {@code UPDATE} statement, and a single statement's WHERE-check-then-write is inherently atomic
 * under Postgres's own row-level locking regardless of what issued it. That's a native SQL-engine
 * guarantee, not the kind of Hibernate-persistence-context-specific behavior that needed a real
 * concurrency test in {@code StockReservationServiceIntegrationTest}, where an unlocked {@code JOIN
 * FETCH} silently shadowed an explicit {@code @Lock} and only a live-thread test caught it. Both
 * methods' Javadoc does document a race-safety guarantee - they exist specifically to close a
 * check-then-write TOCTOU race - but that guarantee is exactly the kind of single-statement
 * atomicity Postgres already provides, not a claim this codebase invented and must separately
 * prove.
 */
class DiningTableRepositoryTest extends AbstractPostgresRepositoryTest {

  @Autowired private TestEntityManager entityManager;
  @Autowired private DiningTableRepository diningTableRepository;

  private DiningTable table(String tableNumber, TableStatus status) {
    return RepositoryTestFixtures.table(entityManager, tableNumber, status);
  }

  private Order order(DiningTable table, OrderStatus status) {
    return RepositoryTestFixtures.order(entityManager, table, status, Instant.now(), null);
  }

  private TableStatus statusOf(Long tableId) {
    return entityManager.find(DiningTable.class, tableId).getStatus();
  }

  @Test
  void occupyIfAvailable_claimsTheTable_whenAvailable() {
    DiningTable t = table("T1", TableStatus.AVAILABLE);

    int updated = diningTableRepository.occupyIfAvailable(t.getId());

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.OCCUPIED));
  }

  @Test
  void occupyIfAvailable_returnsZero_whenAlreadyOccupied() {
    DiningTable t = table("T2", TableStatus.OCCUPIED);

    int updated = diningTableRepository.occupyIfAvailable(t.getId());

    assertAll(
        () -> assertThat(updated).isZero(),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.OCCUPIED));
  }

  @Test
  void occupyIfAvailable_returnsZero_whenTableDoesNotExist() {
    int updated = diningTableRepository.occupyIfAvailable(999_999L);

    assertThat(updated).isZero();
  }

  /**
   * Proves the {@code WHERE} clause is scoped by {@code t.id = :id} rather than matching any
   * AVAILABLE table.
   */
  @Test
  void occupyIfAvailable_doesNotAffectOtherTables() {
    DiningTable t = table("T1b", TableStatus.AVAILABLE);
    DiningTable otherTable = table("T1c", TableStatus.AVAILABLE);

    int updated = diningTableRepository.occupyIfAvailable(t.getId());

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.OCCUPIED),
        () -> assertThat(statusOf(otherTable.getId())).isEqualTo(TableStatus.AVAILABLE));
  }

  private static Stream<OrderStatus> closedStatuses() {
    return OrderStatus.CLOSED_STATUSES.stream();
  }

  @ParameterizedTest
  @MethodSource("closedStatuses")
  void releaseIfAllOrdersClosed_releasesTheTable_whenEveryOrderIsClosed(OrderStatus closedStatus) {
    DiningTable t = table("T3", TableStatus.OCCUPIED);
    order(t, closedStatus);

    int updated =
        diningTableRepository.releaseIfAllOrdersClosed(t.getId(), OrderStatus.CLOSED_STATUSES);

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.AVAILABLE));
  }

  @Test
  void releaseIfAllOrdersClosed_returnsZero_whenOneOrderIsStillNonClosed() {
    DiningTable t = table("T4", TableStatus.OCCUPIED);
    order(t, OrderStatus.PAID);
    order(t, OrderStatus.OPEN);

    int updated =
        diningTableRepository.releaseIfAllOrdersClosed(t.getId(), OrderStatus.CLOSED_STATUSES);

    assertAll(
        () -> assertThat(updated).isZero(),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.OCCUPIED));
  }

  @Test
  void releaseIfAllOrdersClosed_releasesTheTable_whenItHasNoOrdersAtAll() {
    DiningTable t = table("T5", TableStatus.OCCUPIED);

    int updated =
        diningTableRepository.releaseIfAllOrdersClosed(t.getId(), OrderStatus.CLOSED_STATUSES);

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.AVAILABLE));
  }

  @Test
  void releaseIfAllOrdersClosed_returnsZero_whenTableDoesNotExist() {
    int updated =
        diningTableRepository.releaseIfAllOrdersClosed(999_999L, OrderStatus.CLOSED_STATUSES);

    assertThat(updated).isZero();
  }

  /**
   * Proves the {@code NOT EXISTS} subquery is scoped by {@code o.table.id = :id} rather than
   * checking order status globally across every table.
   */
  @Test
  void releaseIfAllOrdersClosed_ignoresNonClosedOrdersOnOtherTables() {
    DiningTable t = table("T6", TableStatus.OCCUPIED);
    order(t, OrderStatus.PAID);
    DiningTable otherTable = table("T7", TableStatus.OCCUPIED);
    order(otherTable, OrderStatus.OPEN);

    int updated =
        diningTableRepository.releaseIfAllOrdersClosed(t.getId(), OrderStatus.CLOSED_STATUSES);

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.AVAILABLE),
        () -> assertThat(statusOf(otherTable.getId())).isEqualTo(TableStatus.OCCUPIED));
  }

  /**
   * Unlike {@link DiningTableRepository#occupyIfAvailable}, this query has no {@code t.status =
   * OCCUPIED} precondition - it only checks order closure and unconditionally writes AVAILABLE. So
   * an already-AVAILABLE table with only closed (or zero) orders still reports 1 row updated, not
   * 0.
   */
  @Test
  void releaseIfAllOrdersClosed_returnsOne_whenTableIsAlreadyAvailable() {
    DiningTable t = table("T8", TableStatus.AVAILABLE);
    order(t, OrderStatus.PAID);

    int updated =
        diningTableRepository.releaseIfAllOrdersClosed(t.getId(), OrderStatus.CLOSED_STATUSES);

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () -> assertThat(statusOf(t.getId())).isEqualTo(TableStatus.AVAILABLE));
  }
}
