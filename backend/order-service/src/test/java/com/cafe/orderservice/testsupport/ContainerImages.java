package com.cafe.orderservice.testsupport;

/**
 * The image of each container that order-service's Testcontainers tests run against, in one place
 * so every test class uses the same image. {@link #POSTGRES} is {@code docker-compose.yml}'s
 * postgres image, at the same Postgres version, major and minor, as the CNPG cluster on GKE ({@code
 * k8s/data-layer/postgres-cluster.yaml}). {@link #KAFKA} is {@code docker-compose.yml}'s broker
 * image, whose Kafka version the Strimzi cluster on GKE ({@code k8s/data-layer/kafka-cluster.yaml})
 * runs too. The comment above each version in its cluster manifest lists every place to change with
 * it.
 */
public final class ContainerImages {

  public static final String POSTGRES =
      "postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636";
  public static final String KAFKA =
      "apache/kafka@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";

  private ContainerImages() {}
}
