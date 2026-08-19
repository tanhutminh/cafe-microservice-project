package com.cafe.orderservice.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.order.OrderRepository;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.table.dto.DiningTableRequest;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for DiningTableService's own logic. DiningTableControllerTest's MockMvc slice stubs
 * this service out entirely (@MockitoBean), so none of its branches ever execute there - this class
 * is where that coverage actually lives, mirroring OrderServiceTest's plain-Mockito style rather
 * than @DataJpaTest, since nothing here needs a real database.
 */
@ExtendWith(MockitoExtension.class)
class DiningTableServiceTest {

  private static final Long TABLE_ID = 3L;

  @Mock private DiningTableRepository diningTableRepository;
  @Mock private OrderRepository orderRepository;

  private DiningTableService diningTableService;

  @BeforeEach
  void setUp() {
    diningTableService = new DiningTableService(diningTableRepository, orderRepository);
  }

  private DiningTable table(TableStatus status) {
    return DiningTable.builder()
        .id(TABLE_ID)
        .tableNumber("Bàn 3")
        .capacity(4)
        .status(status)
        .active(true)
        .build();
  }

  @Test
  void findById_missing_throwsResourceNotFound() {
    when(diningTableRepository.findById(TABLE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> diningTableService.findById(TABLE_ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void create_alwaysStartsAvailableRegardlessOfRequest() {
    DiningTableRequest request = new DiningTableRequest("T5", 4, true);
    ArgumentCaptor<DiningTable> captor = ArgumentCaptor.forClass(DiningTable.class);
    when(diningTableRepository.save(captor.capture()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    diningTableService.create(request);

    assertAll(
        () -> assertThat(captor.getValue().getStatus()).isEqualTo(TableStatus.AVAILABLE),
        () -> assertThat(captor.getValue().getTableNumber()).isEqualTo("T5"),
        () -> assertThat(captor.getValue().getCapacity()).isEqualTo(4));
  }

  @Test
  void update_changesDetailsButNeverTouchesStatus() {
    DiningTable existing = table(TableStatus.OCCUPIED);
    when(diningTableRepository.findById(TABLE_ID)).thenReturn(Optional.of(existing));
    when(diningTableRepository.save(any(DiningTable.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    DiningTable result =
        diningTableService.update(TABLE_ID, new DiningTableRequest("Renamed", 6, false));

    assertAll(
        () -> assertThat(result.getTableNumber()).isEqualTo("Renamed"),
        () -> assertThat(result.getCapacity()).isEqualTo(6),
        () -> assertThat(result.isActive()).isFalse(),
        () -> assertThat(result.getStatus()).isEqualTo(TableStatus.OCCUPIED));
  }

  @Test
  void delete_softDeletesByClearingActiveOnly() {
    DiningTable existing = table(TableStatus.AVAILABLE);
    when(diningTableRepository.findById(TABLE_ID)).thenReturn(Optional.of(existing));
    ArgumentCaptor<DiningTable> captor = ArgumentCaptor.forClass(DiningTable.class);
    when(diningTableRepository.save(captor.capture()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    diningTableService.delete(TABLE_ID);

    assertAll(
        () -> assertThat(captor.getValue().isActive()).isFalse(),
        () -> assertThat(captor.getValue().getStatus()).isEqualTo(TableStatus.AVAILABLE));
  }

  /** Every TableStatus: only AVAILABLE may transition to OCCUPIED. */
  @ParameterizedTest
  @EnumSource(TableStatus.class)
  void occupy_statusGuard(TableStatus status) {
    when(diningTableRepository.findById(TABLE_ID)).thenReturn(Optional.of(table(status)));

    if (status == TableStatus.OCCUPIED) {
      assertThatThrownBy(() -> diningTableService.occupy(TABLE_ID))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Table is already occupied: " + TABLE_ID);
      verify(diningTableRepository, never()).save(any(DiningTable.class));
    } else {
      ArgumentCaptor<DiningTable> captor = ArgumentCaptor.forClass(DiningTable.class);
      when(diningTableRepository.save(captor.capture()))
          .thenAnswer(invocation -> invocation.getArgument(0));

      diningTableService.occupy(TABLE_ID);

      assertThat(captor.getValue().getStatus()).isEqualTo(TableStatus.OCCUPIED);
    }
  }

  private static final Set<OrderStatus> ACTIVE_ORDER_STATUSES =
      EnumSet.of(OrderStatus.OPEN, OrderStatus.PENDING_CONFIRMATION);

  /**
   * Every OrderStatus, not just a representative pair: a table may only be released while no
   * OPEN/PENDING_CONFIRMATION order still claims it - PAID and CANCELLED (and the rest) must not
   * block release, since a paid-but-not-yet-released table or an abandoned cancelled attempt are
   * both legitimately free to release.
   */
  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void release_activeOrderGuard(OrderStatus existingOrderStatus) {
    when(diningTableRepository.findById(TABLE_ID))
        .thenReturn(Optional.of(table(TableStatus.OCCUPIED)));
    when(orderRepository.existsByTable_IdAndStatusIn(eq(TABLE_ID), any()))
        .thenReturn(ACTIVE_ORDER_STATUSES.contains(existingOrderStatus));

    if (ACTIVE_ORDER_STATUSES.contains(existingOrderStatus)) {
      assertThatThrownBy(() -> diningTableService.release(TABLE_ID))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Cannot release table with an active order: " + TABLE_ID);
      verify(diningTableRepository, never()).save(any(DiningTable.class));
    } else {
      ArgumentCaptor<DiningTable> captor = ArgumentCaptor.forClass(DiningTable.class);
      when(diningTableRepository.save(captor.capture()))
          .thenAnswer(invocation -> invocation.getArgument(0));

      diningTableService.release(TABLE_ID);

      assertThat(captor.getValue().getStatus()).isEqualTo(TableStatus.AVAILABLE);
    }
  }
}
