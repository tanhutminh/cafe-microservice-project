package com.cafe.orderservice.order;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

  @Query("SELECT o FROM Order o JOIN FETCH o.table LEFT JOIN FETCH o.items WHERE o.id = :id")
  Optional<Order> findByIdWithDetails(@Param("id") Long id);

  boolean existsByTable_IdAndStatusIn(Long tableId, Collection<OrderStatus> statuses);

  /**
   * The order currently holding a table - any order still unreleased, regardless of status (payment
   * no longer auto-releases the table, so even a PAID order stays current until explicitly
   * released). {@code releasedAt IS NULL} is the discriminator: it's what distinguishes an order
   * belonging to the table's current occupancy from one left over from a prior visit that was paid,
   * released, and the table re-occupied since - cancelling an order always releases its table in
   * the same transaction, so a CANCELLED order's {@code releasedAt} is always set too, making it
   * excluded by this condition alone. LIMIT 1 still matters: more than one order can satisfy the
   * condition at once, whether from a data-inconsistent state or just two orders sharing the same
   * {@code createdAt} - so this deliberately picks the single most recent rather than assuming at
   * most one row can ever match, with {@code id DESC} breaking a {@code createdAt} tie
   * deterministically instead of leaving it to whatever order Postgres happens to return.
   */
  @Query(
      """
            SELECT o FROM Order o JOIN FETCH o.table LEFT JOIN FETCH o.items
            WHERE o.table.id = :tableId AND o.releasedAt IS NULL
            ORDER BY o.createdAt DESC, o.id DESC
            LIMIT 1
            """)
  Optional<Order> findCurrentByTableId(@Param("tableId") Long tableId);

  /**
   * Marks every unreleased order still tied to a table as released, in one statement. Only touches
   * rows where {@code releasedAt} is still null, so an order already marked from an earlier release
   * cycle keeps its original timestamp. Both {@code releasedAt} and {@code updatedAt} are computed
   * by the database itself via {@code statement_timestamp()} rather than a caller-supplied value -
   * Postgres guarantees this function returns the identical result on every call within one
   * statement, so the two columns end up exactly equal, from a single clock read, with no risk of
   * the JVM's and the database's clocks disagreeing. Being a bulk update, it also bypasses
   * {@code @PreUpdate}, which is why {@code updatedAt} is set explicitly here at all.
   *
   * @return the number of orders marked - can legitimately be zero, either because the table has no
   *     orders tied to it at all, or because every tied order is already released (e.g. a repeat
   *     call on a table that was already released).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
            UPDATE Order o SET o.releasedAt = function('statement_timestamp'),
                o.updatedAt = function('statement_timestamp')
            WHERE o.table.id = :tableId AND o.releasedAt IS NULL
            """)
  int markReleased(@Param("tableId") Long tableId);
}
