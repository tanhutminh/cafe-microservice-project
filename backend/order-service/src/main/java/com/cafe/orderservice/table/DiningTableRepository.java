package com.cafe.orderservice.table;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {

    List<DiningTable> findAllByActiveTrueOrderByTableNumberAsc();
}
