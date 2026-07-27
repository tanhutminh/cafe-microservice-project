package com.cafe.orderservice.table;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.order.OrderRepository;
import com.cafe.orderservice.order.OrderStatus;
import com.cafe.orderservice.table.dto.DiningTableRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DiningTableService {

    private static final List<OrderStatus> ACTIVE_ORDER_STATUSES = List.of(OrderStatus.OPEN, OrderStatus.PENDING_CONFIRMATION);

    private final DiningTableRepository diningTableRepository;
    private final OrderRepository orderRepository;

    public DiningTableService(DiningTableRepository diningTableRepository, OrderRepository orderRepository) {
        this.diningTableRepository = diningTableRepository;
        this.orderRepository = orderRepository;
    }

    public List<DiningTable> findAll() {
        return diningTableRepository.findAllByActiveTrueOrderByTableNumberAsc();
    }

    public DiningTable findById(Long id) {
        return diningTableRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("DiningTable", id));
    }

    @Transactional
    public DiningTable create(DiningTableRequest request) {
        DiningTable table = DiningTable.builder()
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

    /** Called by OrderService when a new order is opened on this table. */
    @Transactional
    public void occupy(Long id) {
        DiningTable table = findById(id);
        if (table.getStatus() == TableStatus.OCCUPIED) {
            throw new BusinessRuleException("Table is already occupied: " + id);
        }
        table.setStatus(TableStatus.OCCUPIED);
        diningTableRepository.save(table);
    }

    /**
     * Frees a table — called when an order is cancelled, when a table is moved off it, or by
     * staff explicitly marking it empty (e.g. a customer paid but is still sitting, or has now
     * left). Refuses while an OPEN/PENDING_CONFIRMATION order still claims the table, since two
     * active orders on the same table would break the one-active-order-per-table invariant.
     */
    @Transactional
    public void release(Long id) {
        DiningTable table = findById(id);
        if (orderRepository.existsByTable_IdAndStatusIn(id, ACTIVE_ORDER_STATUSES)) {
            throw new BusinessRuleException("Cannot release table with an active order: " + id);
        }
        table.setStatus(TableStatus.AVAILABLE);
        diningTableRepository.save(table);
    }
}
