package com.cafe.orderservice.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

  /**
   * Wires the observation registry into the {@code WebClient} so requests made through it are
   * instrumented and propagate trace context to the callee.
   */
  @Bean
  public WebClient.Builder observedWebClientBuilder(ObservationRegistry observationRegistry) {
    return WebClient.builder().observationRegistry(observationRegistry);
  }
}
