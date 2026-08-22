package com.cafe.orderservice.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.table.dto.DiningTableRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for DiningTableService's own logic. DiningTableControllerTest's MockMvc slice stubs
 * this service out entirely (@MockitoBean), so none of its branches ever execute there - this class
 * is where that coverage actually lives, mirroring OrderServiceTest's plain-Mockito style rather
 * than @DataJpaTest, since nothing here needs a real database. occupy()/release()'s own status
 * matching lives inside a single conditional-UPDATE query (DiningTableRepository), not branching
 * Java code, so its correctness can only be verified against a real database, outside the scope of
 * this Mockito-based class - what's tested here is that the service correctly turns the query's
 * affected-row-count into either a no-op success or a BusinessRuleException.
 */
@ExtendWith(MockitoExtension.class)
class DiningTableServiceTest {

  private static final Long TABLE_ID = 3L;

  @Mock private DiningTableRepository diningTableRepository;

  private DiningTableService diningTableService;

  @BeforeEach
  void setUp() {
    diningTableService = new DiningTableService(diningTableRepository);
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

  /**
   * The conditional-UPDATE's affected-row-count is the only thing this service branches on: 1 row
   * means the table was AVAILABLE at write time and now isn't; 0 rows means it wasn't AVAILABLE at
   * write time - either it was already OCCUPIED, or (the case a plain read-then-write couldn't
   * catch) someone else's occupy() won a race that landed between this call's own read and write.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void occupy_turnsConditionalUpdateResultIntoSuccessOrBusinessRule(int rowsUpdated) {
    when(diningTableRepository.findById(TABLE_ID))
        .thenReturn(Optional.of(table(TableStatus.AVAILABLE)));
    when(diningTableRepository.occupyIfAvailable(TABLE_ID)).thenReturn(rowsUpdated);

    if (rowsUpdated == 1) {
      diningTableService.occupy(TABLE_ID);
      verify(diningTableRepository).occupyIfAvailable(TABLE_ID);
    } else {
      assertThatThrownBy(() -> diningTableService.occupy(TABLE_ID))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Table is already occupied: " + TABLE_ID);
    }
  }

  /**
   * Same shape as the occupy() case above: 1 row means every order tied to the table was closed at
   * write time; 0 rows means some order still tied to the table isn't CANCELLED/PAID - either a
   * plain read would have caught it too, or (the race a plain read-then-write couldn't catch) an
   * order reached a non-closed status between this call's own check and write.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void release_turnsConditionalUpdateResultIntoSuccessOrBusinessRule(int rowsUpdated) {
    when(diningTableRepository.findById(TABLE_ID))
        .thenReturn(Optional.of(table(TableStatus.OCCUPIED)));
    when(diningTableRepository.releaseIfAllOrdersClosed(eq(TABLE_ID), any()))
        .thenReturn(rowsUpdated);

    if (rowsUpdated == 1) {
      diningTableService.release(TABLE_ID);
      verify(diningTableRepository).releaseIfAllOrdersClosed(TABLE_ID, OrderStatus.CLOSED_STATUSES);
    } else {
      assertThatThrownBy(() -> diningTableService.release(TABLE_ID))
          .isInstanceOf(BusinessRuleException.class)
          .hasMessage("Cannot release table with an active order: " + TABLE_ID);
    }
  }
}
