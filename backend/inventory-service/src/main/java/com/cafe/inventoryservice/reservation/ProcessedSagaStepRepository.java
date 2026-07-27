package com.cafe.inventoryservice.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedSagaStepRepository extends JpaRepository<ProcessedSagaStep, Long> {
}
