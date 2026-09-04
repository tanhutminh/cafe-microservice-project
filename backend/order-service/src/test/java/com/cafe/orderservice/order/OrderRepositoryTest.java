package com.cafe.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.testsupport.AbstractPostgresRepositoryTest;
import com.cafe.orderservice.testsupport.RepositoryTestFixtures;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * {@link OrderRepository#findCurrentByTableId} and {@link OrderRepository#markReleased} depend on
 * JPQL/SQL text whose correctness a Mockito-mocked repository (this codebase's usual test style) is
 * structurally unable to exercise - a mock only verifies a method was called with the right
 * arguments, never what the query text itself actually selects or updates.
 */
class OrderRepositoryTest extends AbstractPostgresRepositoryTest {

  @Autowired private TestEntityManager entityManager;
  @Autowired private OrderRepository orderRepository;

  private DiningTable table(String tableNumber) {
    return RepositoryTestFixtures.occupiedTable(entityManager, tableNumber);
  }

  private Order order(
      DiningTable table, OrderStatus status, Instant createdAt, Instant releasedAt) {
    return RepositoryTestFixtures.order(entityManager, table, status, createdAt, releasedAt);
  }

  @Test
  void findCurrentByTableId_excludesAReleasedOrderEvenIfMostRecent() {
    DiningTable t = table("T1");
    Instant anHourAgo = Instant.now().minusSeconds(3600);
    order(t, OrderStatus.PAID, anHourAgo, anHourAgo.plusSeconds(60)); // stale, prior visit
    Order current = order(t, OrderStatus.OPEN, Instant.now(), null);

    Optional<Order> result = orderRepository.findCurrentByTableId(t.getId());

    assertAll(
        () -> assertThat(result).isPresent(),
        () -> assertThat(result.get().getId()).isEqualTo(current.getId()),
        () -> assertThat(result.get().getReleasedAt()).isNull());
  }

  @Test
  void findCurrentByTableId_picksMostRecentAmongValidCandidates() {
    DiningTable t = table("T3");
    order(t, OrderStatus.OPEN, Instant.now().minusSeconds(120), null);
    Order newest = order(t, OrderStatus.CONFIRMED, Instant.now(), null);

    Optional<Order> result = orderRepository.findCurrentByTableId(t.getId());

    assertAll(
        () -> assertThat(result).isPresent(),
        () -> assertThat(result.get().getId()).isEqualTo(newest.getId()),
        () -> assertThat(result.get().getStatus()).isEqualTo(OrderStatus.CONFIRMED));
  }

  /**
   * Two near-simultaneous requests (see {@link com.cafe.orderservice.order.OrderService}'s own
   * documented race window on order creation) can leave two orders on the same table sharing the
   * exact same {@code createdAt} - {@code id DESC} must break that tie deterministically rather
   * than leaving it to whatever order Postgres happens to return.
   */
  @Test
  void findCurrentByTableId_breaksACreatedAtTieByHighestId() {
    DiningTable t = table("T3b");
    Instant sameInstant = Instant.now();
    order(t, OrderStatus.OPEN, sameInstant, null);
    Order higherId = order(t, OrderStatus.OPEN, sameInstant, null);

    Optional<Order> result = orderRepository.findCurrentByTableId(t.getId());

    assertAll(
        () -> assertThat(result).isPresent(),
        () -> assertThat(result.get().getId()).isEqualTo(higherId.getId()));
  }

  @Test
  void findCurrentByTableId_returnsEmptyWhenNothingMatches() {
    DiningTable t = table("T4");

    assertThat(orderRepository.findCurrentByTableId(t.getId())).isEmpty();
  }

  /**
   * The scenario the moveTable design decision hinges on: a moved order is never released (its
   * {@code releasedAt} stays null throughout), so it must still be found under its new table's id
   * with zero special-casing anywhere in the move itself.
   */
  @Test
  void findCurrentByTableId_findsAMovedOrderUnderItsNewTableEvenThoughNeverReleased() {
    DiningTable oldTable = table("T5");
    DiningTable newTable = table("T6");
    Order moved = order(oldTable, OrderStatus.CONFIRMED, Instant.now(), null);

    moved.setTable(newTable);
    entityManager.flush();

    Optional<Order> result = orderRepository.findCurrentByTableId(newTable.getId());
    assertAll(
        () -> assertThat(result).isPresent(),
        () -> assertThat(result.get().getId()).isEqualTo(moved.getId()),
        () -> assertThat(result.get().getTable().getId()).isEqualTo(newTable.getId()),
        () -> assertThat(result.get().getReleasedAt()).isNull());
  }

  @Test
  void markReleased_stampsOnlyUnreleasedOrdersOnThatTable() {
    DiningTable t = table("T7");
    // Truncated to microseconds: Postgres TIMESTAMPTZ only stores that much precision, so a
    // nanosecond-precision Instant compared against the round-tripped-from-DB value would fail on
    // its trailing digits alone, unrelated to what this test actually checks.
    Instant firstReleasedAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    Order alreadyReleased =
        order(t, OrderStatus.PAID, Instant.now().minusSeconds(700), firstReleasedAt);
    Order unreleased = order(t, OrderStatus.PAID, Instant.now(), null);
    Instant unreleasedOriginalUpdatedAt = unreleased.getUpdatedAt();
    DiningTable otherTable = table("T8");
    Order otherTableOrder = order(otherTable, OrderStatus.PAID, Instant.now(), null);

    int updated = orderRepository.markReleased(t.getId());
    Order refreshedUnreleased = entityManager.find(Order.class, unreleased.getId());

    assertAll(
        () -> assertThat(updated).isEqualTo(1),
        () ->
            assertThat(entityManager.find(Order.class, alreadyReleased.getId()).getReleasedAt())
                .isEqualTo(firstReleasedAt),
        () -> assertThat(refreshedUnreleased.getReleasedAt()).isNotNull(),
        () ->
            assertThat(refreshedUnreleased.getUpdatedAt())
                .isNotEqualTo(unreleasedOriginalUpdatedAt),
        // Both columns are computed by the same statement_timestamp() read in the same
        // statement, so they're not just close - Postgres guarantees they're identical.
        () ->
            assertThat(refreshedUnreleased.getReleasedAt())
                .isEqualTo(refreshedUnreleased.getUpdatedAt()),
        () ->
            assertThat(entityManager.find(Order.class, otherTableOrder.getId()).getReleasedAt())
                .isNull());
  }

  @Test
  void markReleased_returnsZeroForATableWithNoOrders() {
    DiningTable t = table("T9");

    int updated = orderRepository.markReleased(t.getId());

    assertThat(updated).isZero();
  }

  @Test
  void markReleased_returnsZeroWhenAllOrdersAlreadyReleased() {
    DiningTable t = table("T10");
    Instant originalReleasedAt = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    Order alreadyReleased =
        order(t, OrderStatus.PAID, Instant.now().minusSeconds(700), originalReleasedAt);

    int updated = orderRepository.markReleased(t.getId());

    assertAll(
        () -> assertThat(updated).isZero(),
        () ->
            assertThat(entityManager.find(Order.class, alreadyReleased.getId()).getReleasedAt())
                .isEqualTo(originalReleasedAt));
  }
}
