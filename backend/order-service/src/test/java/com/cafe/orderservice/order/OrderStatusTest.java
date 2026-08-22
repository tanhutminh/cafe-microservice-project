package com.cafe.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

  @Test
  void closedAndNonClosedStatuses_partitionEveryStatusWithNoOverlap() {
    assertAll(
        () ->
            assertThat(OrderStatus.CLOSED_STATUSES)
                .containsExactlyInAnyOrder(OrderStatus.PAID, OrderStatus.CANCELLED),
        () ->
            assertThat(OrderStatus.NON_CLOSED_STATUSES)
                .containsExactlyInAnyOrder(
                    OrderStatus.OPEN,
                    OrderStatus.PENDING_CONFIRMATION,
                    OrderStatus.CONFIRMED,
                    OrderStatus.PAYMENT_PENDING),
        () ->
            assertThat(OrderStatus.CLOSED_STATUSES)
                .doesNotContainAnyElementsOf(OrderStatus.NON_CLOSED_STATUSES));
  }
}
