package com.cafe.inventoryservice.testsupport;

/**
 * The image of each container that inventory-service's Testcontainers tests run against, in one
 * place so every test class uses the same version. {@link #POSTGRES} is the same Postgres version,
 * major and minor, as the CNPG cluster on GKE ({@code k8s/data-layer/postgres-cluster.yaml}) and
 * {@code docker-compose.yml}. The comment above the version in that cluster manifest lists every
 * place to change with it.
 */
public final class ContainerImages {

  public static final String POSTGRES = "postgres:18.4";

  private ContainerImages() {}
}
