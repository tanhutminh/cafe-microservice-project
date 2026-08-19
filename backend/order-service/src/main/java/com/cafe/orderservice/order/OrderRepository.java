package com.cafe.orderservice.order;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

  @Query("SELECT o FROM Order o JOIN FETCH o.table LEFT JOIN FETCH o.items WHERE o.id = :id")
  Optional<Order> findByIdWithDetails(@Param("id") Long id);

  /**
   * Used to guard both table release/move (a narrower OPEN/PENDING_CONFIRMATION-only check) and
   * order creation (a wider set — see {@link OrderService#createOrderWithItems} — which also treats
   * CONFIRMED/PAYMENT_PENDING as in progress).
   */
  boolean existsByTable_IdAndStatusIn(Long tableId, Collection<OrderStatus> statuses);

  /**
   * The order currently holding a table — OPEN/PENDING_CONFIRMATION (actively being worked on) or
   * PAID (settled but the table hasn't been released yet, since payment no longer auto-releases
   * it). CANCELLED is excluded because cancelling always releases the table immediately, so no
   * CANCELLED order is ever the "current" one for an OCCUPIED table. LIMIT 1 matters here: a table
   * can accumulate more than one non-cancelled order over time (an old PAID one, then a new one
   * after release+reoccupy), so this picks the single most recent rather than assuming at most one
   * row can ever match.
   */
  @Query(
      """
            SELECT o FROM Order o JOIN FETCH o.table LEFT JOIN FETCH o.items
            WHERE o.table.id = :tableId AND o.status <> com.cafe.orderservice.order.OrderStatus.CANCELLED
            ORDER BY o.createdAt DESC
            LIMIT 1
            """)
  Optional<Order> findCurrentByTableId(@Param("tableId") Long tableId);
}
