package com.cafe.orderservice.testsupport;

import com.cafe.orderservice.order.Order;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.TableStatus;
import java.time.Instant;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

/**
 * Shared persist-and-return fixture builders for order-service's real-Postgres (Testcontainers)
 * repository tests. Every method persists, flushes, and re-fetches its entity through the given
 * {@link TestEntityManager} rather than returning an unmanaged instance, so callers get back an
 * entity with a real generated id and any database-computed columns already populated.
 */
public final class RepositoryTestFixtures {

  private RepositoryTestFixtures() {}

  public static DiningTable table(
      TestEntityManager entityManager, String tableNumber, TableStatus status) {
    DiningTable table =
        DiningTable.builder()
            .tableNumber(tableNumber)
            .capacity(4)
            .status(status)
            .active(true)
            .build();
    return entityManager.persistFlushFind(table);
  }

  /** Convenience overload for the common case of an already-occupied table. */
  public static DiningTable occupiedTable(TestEntityManager entityManager, String tableNumber) {
    return table(entityManager, tableNumber, TableStatus.OCCUPIED);
  }

  public static Order order(
      TestEntityManager entityManager,
      DiningTable table,
      OrderStatus status,
      Instant createdAt,
      Instant releasedAt) {
    Order order =
        Order.builder()
            .table(table)
            .status(status)
            .createdAt(createdAt)
            .releasedAt(releasedAt)
            .build();
    return entityManager.persistFlushFind(order);
  }
}
