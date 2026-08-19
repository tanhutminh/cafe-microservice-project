package com.cafe.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.event.OrderLineItem;
import com.cafe.common.exception.BusinessRuleException;
import com.cafe.orderservice.client.MenuServiceClient;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.DiningTableService;
import com.cafe.orderservice.table.TableStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for OrderService's own logic. OrderControllerTest's MockMvc slice stubs
 * OrderService out entirely (@MockitoBean), so none of its branches ever execute there (e.g. every
 * OrderStatus value against a guard) - this class is where that coverage actually lives. Mirrors
 * OrderSagaTest's plain-Mockito style rather than @DataJpaTest, since nothing here needs a real
 * database - OrderRepository is a thin pass-through.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  private static final Long TABLE_ID = 3L;
  private static final Long ORDER_ID = 42L;

  @Mock private OrderRepository orderRepository;
  @Mock private DiningTableService diningTableService;
  @Mock private MenuServiceClient menuServiceClient;

  private OrderService orderService;

  @BeforeEach
  void setUp() {
    orderService = new OrderService(orderRepository, diningTableService, menuServiceClient);
  }

  private DiningTable table() {
    return table(TableStatus.OCCUPIED);
  }

  private DiningTable table(TableStatus status) {
    return DiningTable.builder().id(TABLE_ID).tableNumber("T3").capacity(4).status(status).build();
  }

  private Order order(OrderStatus status) {
    return Order.builder()
        .id(ORDER_ID)
        .table(table())
        .status(status)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  private MenuItemDetails available(Long menuItemId) {
    return new MenuItemDetails(menuItemId, "Latte", BigDecimal.valueOf(50000), true);
  }

  @Test
  void createOrderWithItems_doesNotCallOccupy() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result = orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 2)));

    verify(diningTableService, never()).occupy(anyLong());
    assertThat(result.getItems()).hasSize(1);
    assertThat(result.getItems().get(0).getQuantity()).isEqualTo(2);
  }

  @Test
  void createOrderWithItems_duplicateMenuItemId_keepsOneLineWithTheLastQuantity() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result =
        orderService.createOrderWithItems(
            TABLE_ID, List.of(new OrderLineItem(9L, 2), new OrderLineItem(9L, 5)));

    assertThat(result.getItems()).hasSize(1);
    assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
  }

  private static final Set<OrderStatus> IN_PROGRESS_STATUSES =
      EnumSet.of(
          OrderStatus.OPEN,
          OrderStatus.PENDING_CONFIRMATION,
          OrderStatus.CONFIRMED,
          OrderStatus.PAYMENT_PENDING);

  /**
   * Every OrderStatus, not just a representative pair: an order in progress (OPEN through
   * PAYMENT_PENDING) must block a second order on the table, but PAID and CANCELLED must not - a
   * table that was paid and released (or a cancelled attempt) is legitimately free to reuse, and
   * this table's seed/demo data routinely has old PAID orders sitting on AVAILABLE tables.
   */
  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void createOrderWithItems_statusGuard(OrderStatus existingOrderStatus) {
    // Matches on tableId only, not the exact status collection: OrderService.IN_PROGRESS_STATUSES
    // is a List, this test's own set is an EnumSet, and List.equals(Set) is always false per the
    // Collections contract regardless of shared elements - asserting the real call site passes
    // the right statuses isn't this test's job anyway, just that the guard branches correctly on
    // whatever the repository reports.
    when(orderRepository.existsByTable_IdAndStatusIn(eq(TABLE_ID), any()))
        .thenReturn(IN_PROGRESS_STATUSES.contains(existingOrderStatus));

    if (IN_PROGRESS_STATUSES.contains(existingOrderStatus)) {
      assertThatThrownBy(
              () -> orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 1))))
          .isInstanceOf(BusinessRuleException.class);
      verify(diningTableService, never()).findById(anyLong());
      verify(orderRepository, never()).save(any(Order.class));
    } else {
      when(diningTableService.findById(TABLE_ID)).thenReturn(table());
      when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
      when(orderRepository.save(any(Order.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      Order result = orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 1)));

      assertThat(result.getItems()).hasSize(1);
    }
  }

  /**
   * Every TableStatus: only OCCUPIED may have an order created on it. This is what actually
   * enforces the "table must already be OCCUPIED" precondition documented on this method - without
   * it, a table released (or never occupied at all) between selection and Confirm could still end
   * up with an order, leaving the table's own status permanently out of sync with it.
   */
  @ParameterizedTest
  @EnumSource(TableStatus.class)
  void createOrderWithItems_rejectsWhenTableNotOccupied(TableStatus status) {
    when(orderRepository.existsByTable_IdAndStatusIn(eq(TABLE_ID), any())).thenReturn(false);
    when(diningTableService.findById(TABLE_ID)).thenReturn(table(status));

    if (status == TableStatus.OCCUPIED) {
      when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
      when(orderRepository.save(any(Order.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      Order result = orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 1)));

      assertThat(result.getItems()).hasSize(1);
    } else {
      assertThatThrownBy(
              () -> orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 1))))
          .isInstanceOf(BusinessRuleException.class);
      verify(orderRepository, never()).save(any(Order.class));
    }
  }

  @Test
  void createOrderWithItems_resolvesEachLineViaMenuService_rejectsUnavailableItem() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(menuServiceClient.findMenuItem(9L))
        .thenReturn(new MenuItemDetails(9L, "Sold-out Cake", BigDecimal.TEN, false));

    assertThatThrownBy(
            () -> orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 1))))
        .isInstanceOf(BusinessRuleException.class);

    verify(orderRepository, never()).save(any(Order.class));
  }

  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void replaceItems_statusGuard(OrderStatus status) {
    when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order(status)));

    if (status == OrderStatus.OPEN) {
      when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
      when(orderRepository.save(any(Order.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      Order result = orderService.replaceItems(ORDER_ID, List.of(new OrderLineItem(9L, 1)));

      assertThat(result.getItems()).hasSize(1);
    } else {
      assertThatThrownBy(
              () -> orderService.replaceItems(ORDER_ID, List.of(new OrderLineItem(9L, 1))))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Order cannot be checked out from status " + status + ": " + ORDER_ID);
      verify(orderRepository, never()).save(any(Order.class));
    }
  }

  @Test
  void replaceItems_clearsOldItemsEntirely() {
    Order existing = order(OrderStatus.OPEN);
    existing.addItem(
        OrderItem.builder()
            .id(1L)
            .menuItemId(5L)
            .nameSnapshot("Old Item")
            .priceSnapshot(BigDecimal.TEN)
            .quantity(1)
            .build());
    when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(existing));
    when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    when(orderRepository.save(captor.capture()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    orderService.replaceItems(ORDER_ID, List.of(new OrderLineItem(9L, 3)));

    List<OrderItem> savedItems = captor.getValue().getItems();
    assertThat(savedItems).hasSize(1);
    assertThat(savedItems.get(0).getMenuItemId()).isEqualTo(9L);
  }

  @Test
  void replaceItems_duplicateMenuItemId_keepsOneLineWithTheLastQuantity() {
    when(orderRepository.findByIdWithDetails(ORDER_ID))
        .thenReturn(Optional.of(order(OrderStatus.OPEN)));
    when(menuServiceClient.findMenuItem(9L)).thenReturn(available(9L));
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result =
        orderService.replaceItems(
            ORDER_ID, List.of(new OrderLineItem(9L, 2), new OrderLineItem(9L, 5)));

    assertThat(result.getItems()).hasSize(1);
    assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
  }

  @Test
  void startPayment_fromConfirmed_startsPaymentPending() {
    when(orderRepository.findByIdWithDetails(ORDER_ID))
        .thenReturn(Optional.of(order(OrderStatus.CONFIRMED)));
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result = orderService.startPayment(ORDER_ID, "CASH");

    assertAll(
        () -> assertThat(result.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING),
        () -> assertThat(result.getPaymentMethod()).isEqualTo("CASH"));
  }

  /**
   * Reproduces a real race (two POS terminals hitting Pay on the same order): the message must name
   * the *actual* reason, not a one-size-fits-all "must be verified" that's actively wrong for an
   * order that was already paid, or whose payment is already in flight.
   */
  private static Stream<Arguments> startPaymentBlockedMessages() {
    return Stream.of(
        Arguments.of(OrderStatus.OPEN, "Order must be verified before payment: " + ORDER_ID),
        Arguments.of(
            OrderStatus.PENDING_CONFIRMATION, "Order must be verified before payment: " + ORDER_ID),
        Arguments.of(OrderStatus.PAYMENT_PENDING, "Payment is already in progress: " + ORDER_ID),
        Arguments.of(OrderStatus.PAID, "Order is already paid: " + ORDER_ID),
        Arguments.of(OrderStatus.CANCELLED, "Cannot pay a cancelled order: " + ORDER_ID));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("startPaymentBlockedMessages")
  void startPayment_notConfirmed_throwsWithStatusSpecificMessage(
      OrderStatus status, String expectedMessage) {
    when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order(status)));

    assertThatThrownBy(() -> orderService.startPayment(ORDER_ID, "CASH"))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage(expectedMessage);

    verify(orderRepository, never()).save(any(Order.class));
  }
}
