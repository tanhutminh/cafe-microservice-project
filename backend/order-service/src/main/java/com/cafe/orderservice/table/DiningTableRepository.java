package com.cafe.orderservice.table;

import com.cafe.orderservice.order.OrderStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {

  List<DiningTable> findAllByActiveTrueOrderByTableNumberAsc();

  /**
   * Atomically claims a table for a new order-in-progress: the status check and the write are one
   * statement, so two near-simultaneous calls for the same table can't both see AVAILABLE and both
   * succeed - the loser's WHERE clause matches zero rows once the winner's UPDATE has committed.
   * {@code flushAutomatically} matters when this runs inside a larger transaction that has already
   * written pending changes which this table's row (or a joined query) might depend on. A bulk
   * update like this one doesn't get Hibernate's usual auto-flush-before-query behavior by default,
   * so {@code flushAutomatically} asks for it explicitly. {@code clearAutomatically} drops the
   * persistence context afterward so a subsequent read in the same transaction sees the committed
   * row instead of a stale cached one.
   *
   * @return the number of rows updated - 0 means the table was already OCCUPIED. Table existence is
   *     not verified by this query.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE DiningTable t SET t.status = com.cafe.orderservice.table.TableStatus.OCCUPIED
      WHERE t.id = :id AND t.status = com.cafe.orderservice.table.TableStatus.AVAILABLE
      """)
  int occupyIfAvailable(@Param("id") Long id);

  /**
   * Atomically frees a table only if every order ever tied to it is in a closed status - the status
   * check and the write are one statement, so a race between this and an order reaching a
   * non-closed status can't leave the table wrongly released. Same {@code
   * flushAutomatically}/{@code clearAutomatically} reasoning as {@link #occupyIfAvailable}.
   *
   * @return the number of rows updated - 0 means some order on the table is still non-closed. Table
   *     existence is not verified by this query.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE DiningTable t SET t.status = com.cafe.orderservice.table.TableStatus.AVAILABLE
      WHERE t.id = :id AND NOT EXISTS (
        SELECT 1 FROM Order o WHERE o.table.id = :id AND o.status NOT IN :closedStatuses
      )
      """)
  int releaseIfAllOrdersClosed(
      @Param("id") Long id, @Param("closedStatuses") Collection<OrderStatus> closedStatuses);
}
