package com.cafe.orderservice.table;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.order.OrderRepository;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.table.dto.DiningTableRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiningTableService {

  private final DiningTableRepository diningTableRepository;
  private final OrderRepository orderRepository;

  public DiningTableService(
      DiningTableRepository diningTableRepository, OrderRepository orderRepository) {
    this.diningTableRepository = diningTableRepository;
    this.orderRepository = orderRepository;
  }

  public List<DiningTable> findAll() {
    return diningTableRepository.findAllByActiveTrueOrderByTableNumberAsc();
  }

  public DiningTable findById(Long id) {
    return diningTableRepository
        .findById(id)
        .orElseThrow(() -> ResourceNotFoundException.of("DiningTable", id));
  }

  @Transactional
  public DiningTable create(DiningTableRequest request) {
    DiningTable table =
        DiningTable.builder()
            .tableNumber(request.tableNumber())
            .capacity(request.capacity())
            .status(TableStatus.AVAILABLE)
            .active(request.active())
            .build();
    return diningTableRepository.save(table);
  }

  @Transactional
  public DiningTable update(Long id, DiningTableRequest request) {
    DiningTable table = findById(id);
    table.setTableNumber(request.tableNumber());
    table.setCapacity(request.capacity());
    table.setActive(request.active());
    return diningTableRepository.save(table);
  }

  @Transactional
  public void delete(Long id) {
    DiningTable table = findById(id);
    table.setActive(false);
    diningTableRepository.save(table);
  }

  /**
   * Marks an AVAILABLE table OCCUPIED - the first step of starting an order on it, before any Order
   * row exists yet. See {@link DiningTableRepository#occupyIfAvailable} for the atomic guarantee
   * this relies on: at most one of two near-simultaneous calls for the same table can succeed.
   */
  @Transactional
  public void occupy(Long id) {
    findById(id);
    if (diningTableRepository.occupyIfAvailable(id) == 0) {
      throw new BusinessRuleException("Table is already occupied: " + id);
    }
  }

  /**
   * Frees a table — called when an order is cancelled, when a table is moved off it, or by staff
   * explicitly marking it empty (e.g. a customer paid but is still sitting, or has now left).
   * Refuses while any order still tied to the table has a non-closed {@link OrderStatus} (see
   * {@link OrderStatus#NON_CLOSED_STATUSES}), since two non-closed orders on the same table would
   * break the one-order-per-table invariant. See {@link
   * DiningTableRepository#releaseIfAllOrdersClosed} for the atomic guarantee this relies on.
   *
   * <p>Also marks every order still tied to the table as released (see {@link
   * OrderRepository#markReleased}) - a deliberate, table-package-reaches-into-order-package
   * exception, since every code path that frees a table must also release its lingering orders, and
   * centralizing that here guarantees no direct caller of this method can forget it.
   */
  @Transactional
  public void release(Long id) {
    findById(id);
    if (diningTableRepository.releaseIfAllOrdersClosed(id, OrderStatus.CLOSED_STATUSES) == 0) {
      throw new BusinessRuleException("Cannot release table with an active order: " + id);
    }
    orderRepository.markReleased(id);
  }
}
