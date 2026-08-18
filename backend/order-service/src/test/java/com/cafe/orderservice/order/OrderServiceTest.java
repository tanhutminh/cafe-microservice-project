package com.cafe.orderservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.event.OrderLineItem;
import com.cafe.common.exception.BusinessRuleException;
import com.cafe.orderservice.client.MenuServiceClient;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.DiningTableService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for OrderService's own logic - previously only exercised indirectly through
 * OrderControllerTest's MockMvc slice, which doesn't reach branch-level coverage (e.g. every
 * OrderStatus value against a guard). Mirrors OrderSagaTest's plain-Mockito style rather
 * than @DataJpaTest, since nothing here needs a real database - OrderRepository is a thin
 * pass-through.
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
    return DiningTable.builder().id(TABLE_ID).tableNumber("T3").capacity(4).build();
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
          .isInstanceOf(BusinessRuleException.class);
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
}
