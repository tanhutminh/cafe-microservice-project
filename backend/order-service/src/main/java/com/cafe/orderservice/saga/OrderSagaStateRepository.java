package com.cafe.orderservice.saga;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, Long> {
}
