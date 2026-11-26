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
      "postgres@sha256:64c8b0d31e4ab6ea665e8426ea406a94bf52de3c05b3a4a8bf713704eefa9328";
  public static final String KAFKA =
      "apache/kafka@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";

  private ContainerImages() {}
}
