package com.cafe.orderservice.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, Long> {

    /** Candidates for OrderSagaReconciliationJob: still waiting on a reply (either leg) past the stuck threshold. */
    List<OrderSagaState> findByStepInAndUpdatedAtBefore(Collection<SagaStep> steps, Instant threshold);
}
