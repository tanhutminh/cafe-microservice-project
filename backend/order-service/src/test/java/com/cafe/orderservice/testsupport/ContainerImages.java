package com.cafe.orderservice.testsupport;

/**
 * The image of each container that order-service's Testcontainers tests run against, in one place
 * so every test class uses the same version. {@link #POSTGRES} is the same Postgres version, major
 * and minor, as the CNPG cluster on GKE ({@code k8s/data-layer/postgres-cluster.yaml}) and {@code
 * docker-compose.yml}. {@link #KAFKA} is {@code docker-compose.yml}'s broker image, whose Kafka
 * version the Strimzi cluster on GKE ({@code k8s/data-layer/kafka-cluster.yaml}) runs too. The
 * comment above each version in its cluster manifest lists every place to change with it.
 */
public final class ContainerImages {

  public static final String POSTGRES = "postgres:18.4";
  public static final String KAFKA = "apache/kafka:4.3.1";

  private ContainerImages() {}
}
