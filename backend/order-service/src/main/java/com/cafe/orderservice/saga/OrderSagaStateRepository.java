package com.cafe.orderservice.saga;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, Long> {

  List<OrderSagaState> findByStepInAndUpdatedAtBefore(
      Collection<SagaStep> steps, Instant threshold);
}
