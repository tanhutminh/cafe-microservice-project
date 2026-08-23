package com.cafe.orderservice.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

  /**
   * Wires the observation registry into the load-balanced {@code WebClient} so requests made
   * through it are instrumented and propagate trace context to the callee.
   */
  @Bean
  @LoadBalanced
  public WebClient.Builder loadBalancedWebClientBuilder(ObservationRegistry observationRegistry) {
    return WebClient.builder().observationRegistry(observationRegistry);
  }
}
