package com.cafe.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.event.OrderLineItem;
import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.client.MenuServiceClient;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.DiningTableService;
import com.cafe.orderservice.table.TableStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final Long NEW_TABLE_ID = 4L;
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

  private DiningTable newTable(TableStatus status) {
    return DiningTable.builder()
        .id(NEW_TABLE_ID)
        .tableNumber("T4")
        .capacity(4)
        .status(status)
        .build();
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
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
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
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result =
        orderService.createOrderWithItems(
            TABLE_ID, List.of(new OrderLineItem(9L, 2), new OrderLineItem(9L, 5)));

    assertThat(result.getItems()).hasSize(1);
    assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
  }

  /**
   * Every OrderStatus, not just a representative pair: an order in progress (OPEN through
   * PAYMENT_PENDING) must block a second order on the table, but PAID and CANCELLED must not - a
   * table that was paid and released (or a cancelled attempt) is legitimately free to reuse, and
   * this table's seed/demo data routinely has old PAID orders sitting on AVAILABLE tables.
   */
  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void createOrderWithItems_statusGuard(OrderStatus existingOrderStatus) {
    boolean tableHasOrderInProgress = OrderStatus.NON_CLOSED_STATUSES.contains(existingOrderStatus);
    when(orderRepository.existsByTable_IdAndStatusIn(eq(TABLE_ID), any()))
        .thenReturn(tableHasOrderInProgress);

    if (tableHasOrderInProgress) {
      assertThatThrownBy(
              () -> orderService.createOrderWithItems(TABLE_ID, List.of(new OrderLineItem(9L, 1))))
          .isInstanceOf(BusinessRuleException.class);
      verify(diningTableService, never()).findById(anyLong());
      verify(orderRepository, never()).save(any(Order.class));
    } else {
      when(diningTableService.findById(TABLE_ID)).thenReturn(table());
      when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
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
      when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
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
  void createOrderWithItems_emptyItems_neverCallsMenuService() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result = orderService.createOrderWithItems(TABLE_ID, List.of());

    assertAll(
        () -> assertThat(result.getItems()).isEmpty(),
        () -> verify(menuServiceClient, never()).findMenuItemsAsMap(any()));
  }

  /**
   * Guards against reintroducing the N+1: with two distinct line items, menu-service should be
   * asked once for both ids together, not once per line.
   */
  @Test
  void createOrderWithItems_resolvesAllLinesInOneBatchCall() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L, 10L)))
        .thenReturn(Map.of(9L, available(9L), 10L, available(10L)));
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result =
        orderService.createOrderWithItems(
            TABLE_ID, List.of(new OrderLineItem(9L, 1), new OrderLineItem(10L, 2)));

    assertAll(
        () -> assertThat(result.getItems()).hasSize(2),
        () -> verify(menuServiceClient, times(1)).findMenuItemsAsMap(any()));
  }

  /**
   * MenuServiceClient#findMenuItemsAsMap validates every requested id exists before this method's
   * loop ever reaches a per-line availability check - so a missing id surfaces as
   * ResourceNotFoundException even when an earlier line in the same request also has its own
   * problem (e.g. unavailable), since the batch call itself fails first.
   */
  @Test
  void createOrderWithItems_missingMenuItem_failsBeforeAnyAvailabilityCheck() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L, 99L)))
        .thenThrow(ResourceNotFoundException.of("MenuItem", 99L));

    assertThatThrownBy(
            () ->
                orderService.createOrderWithItems(
                    TABLE_ID, List.of(new OrderLineItem(9L, 1), new OrderLineItem(99L, 1))))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void createOrderWithItems_resolvesEachLineViaMenuService_rejectsUnavailableItem() {
    when(diningTableService.findById(TABLE_ID)).thenReturn(table());
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L)))
        .thenReturn(Map.of(9L, new MenuItemDetails(9L, "Sold-out Cake", BigDecimal.TEN, false)));

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
      when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
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
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
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
    when(menuServiceClient.findMenuItemsAsMap(List.of(9L))).thenReturn(Map.of(9L, available(9L)));
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

  /**
   * Every OrderStatus: PENDING_CONFIRMATION/PAYMENT_PENDING block while a saga leg is in flight
   * (the saga tracks the order, not the table), CANCELLED blocks a cancelled order, everything else
   * succeeds - including PAID, since pay-first-then-dine means a paid-but-not-yet-released order
   * can still legitimately move tables.
   */
  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void moveTable_statusGuard(OrderStatus status) {
    when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order(status)));

    if (status == OrderStatus.PENDING_CONFIRMATION || status == OrderStatus.PAYMENT_PENDING) {
      assertThatThrownBy(() -> orderService.moveTable(ORDER_ID, NEW_TABLE_ID))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Cannot move table while a saga step is in progress: " + ORDER_ID);
      verify(diningTableService, never()).occupy(anyLong());
    } else if (status == OrderStatus.CANCELLED) {
      assertThatThrownBy(() -> orderService.moveTable(ORDER_ID, NEW_TABLE_ID))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Cannot move a cancelled order: " + ORDER_ID);
      verify(diningTableService, never()).occupy(anyLong());
    } else {
      when(diningTableService.findById(NEW_TABLE_ID)).thenReturn(newTable(TableStatus.AVAILABLE));
      when(orderRepository.save(any(Order.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      Order result = orderService.moveTable(ORDER_ID, NEW_TABLE_ID);

      assertAll(
          () -> assertThat(result.getTable().getId()).isEqualTo(NEW_TABLE_ID),
          () -> verify(diningTableService).occupy(NEW_TABLE_ID),
          () -> verify(diningTableService).release(TABLE_ID));
    }
  }

  @Test
  void moveTable_sameTable_returnsWithoutSideEffects() {
    Order order = order(OrderStatus.OPEN);
    when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(order));

    Order result = orderService.moveTable(ORDER_ID, TABLE_ID);

    assertAll(
        () -> assertThat(result).isSameAs(order),
        () -> verify(diningTableService, never()).occupy(anyLong()),
        () -> verify(diningTableService, never()).release(anyLong()),
        () -> verify(orderRepository, never()).save(any(Order.class)));
  }

  /**
   * Regression test for a bug where moveTable returned the pre-save Order reference instead of
   * orderRepository.save()'s result: occupy()'s underlying query clears the persistence context
   * (see DiningTableRepository#occupyIfAvailable), detaching order, so save() merges it into a
   * separate managed instance rather than mutating it in place - returning the stale reference
   * would silently drop anything JPA computes only on that managed copy (e.g. @PreUpdate
   * timestamps).
   */
  @Test
  void moveTable_returnsTheSavedInstance_notTheStaleReferenceBeforeSave() {
    Order original = order(OrderStatus.OPEN);
    Order saved = order(OrderStatus.OPEN);
    when(orderRepository.findByIdWithDetails(ORDER_ID)).thenReturn(Optional.of(original));
    when(diningTableService.findById(NEW_TABLE_ID)).thenReturn(newTable(TableStatus.AVAILABLE));
    when(orderRepository.save(original)).thenReturn(saved);

    Order result = orderService.moveTable(ORDER_ID, NEW_TABLE_ID);

    assertAll(
        () -> assertThat(result).isSameAs(saved), () -> assertThat(result).isNotSameAs(original));
  }
}
