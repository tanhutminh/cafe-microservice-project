package com.cafe.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthCheckRequestContextTest {

  @Test
  void isHealthCheck_falseByDefault() {
    assertThat(HealthCheckRequestContext.isHealthCheck()).isFalse();
  }

  @Test
  void mark_setsIsHealthCheckTrueOnThisThread() {
    HealthCheckRequestContext.mark();
    try {
      assertThat(HealthCheckRequestContext.isHealthCheck()).isTrue();
    } finally {
      HealthCheckRequestContext.clear();
    }
  }

  @Test
  void clear_resetsIsHealthCheckToFalse() {
    HealthCheckRequestContext.mark();
    HealthCheckRequestContext.clear();

    assertThat(HealthCheckRequestContext.isHealthCheck()).isFalse();
  }
}
