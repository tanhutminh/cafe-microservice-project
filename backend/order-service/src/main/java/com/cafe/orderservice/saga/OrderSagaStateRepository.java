package com.cafe.orderservice.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, Long> {

    /** Candidates for OrderSagaReconciliationJob: still waiting on a reply past the stuck threshold. */
    List<OrderSagaState> findByStepAndUpdatedAtBefore(SagaStep step, Instant threshold);
}
