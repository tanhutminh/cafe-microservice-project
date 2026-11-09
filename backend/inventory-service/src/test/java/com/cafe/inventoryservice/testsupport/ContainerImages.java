package com.cafe.inventoryservice.testsupport;

/**
 * The image of each container that inventory-service's Testcontainers tests run against, in one
 * place so every test class uses the same image. {@link #POSTGRES} is {@code docker-compose.yml}'s
 * postgres image, at the same Postgres version, major and minor, as the CNPG cluster on GKE ({@code
 * k8s/data-layer/postgres-cluster.yaml}). The comment above the version in that cluster manifest
 * lists every place to change with it.
 */
public final class ContainerImages {

  public static final String POSTGRES =
      "postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636";

  private ContainerImages() {}
}
