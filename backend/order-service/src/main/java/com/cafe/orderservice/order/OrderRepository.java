package com.cafe.orderservice.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("SELECT o FROM Order o JOIN FETCH o.table LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    /** Used to guard table release/move — a table must not be freed while an order is still active on it. */
    boolean existsByTable_IdAndStatusIn(Long tableId, Collection<OrderStatus> statuses);

    @Query("""
            SELECT o FROM Order o JOIN FETCH o.table LEFT JOIN FETCH o.items
            WHERE o.table.id = :tableId AND o.status NOT IN (com.cafe.orderservice.order.OrderStatus.PAID, com.cafe.orderservice.order.OrderStatus.CANCELLED)
            ORDER BY o.createdAt DESC
            """)
    Optional<Order> findActiveByTableId(@Param("tableId") Long tableId);
}
