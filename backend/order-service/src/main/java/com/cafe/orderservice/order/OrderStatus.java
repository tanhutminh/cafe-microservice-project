package com.cafe.orderservice.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
  OPEN,
  PENDING_CONFIRMATION,
  CONFIRMED,
  PAYMENT_PENDING,
  PAID,
  CANCELLED;

  /** Statuses for orders that have been closed out (paid or cancelled). */
  public static final Set<OrderStatus> CLOSED_STATUSES =
      Collections.unmodifiableSet(EnumSet.of(PAID, CANCELLED));

  /**
   * Statuses for which an order has not yet been closed out — the exact complement of {@link
   * #CLOSED_STATUSES}.
   */
  public static final Set<OrderStatus> NON_CLOSED_STATUSES =
      Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(CLOSED_STATUSES)));
}
