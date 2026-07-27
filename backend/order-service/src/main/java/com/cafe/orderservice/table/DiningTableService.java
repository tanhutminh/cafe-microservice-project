package com.cafe.orderservice.table;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.table.dto.DiningTableRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DiningTableService {

    private final DiningTableRepository diningTableRepository;

    public DiningTableService(DiningTableRepository diningTableRepository) {
        this.diningTableRepository = diningTableRepository;
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

    /** Called by OrderService when an order on this table is paid or cancelled. */
    @Transactional
    public void release(Long id) {
        DiningTable table = findById(id);
        table.setStatus(TableStatus.AVAILABLE);
        diningTableRepository.save(table);
    }
}
