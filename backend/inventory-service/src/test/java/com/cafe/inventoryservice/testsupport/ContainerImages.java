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
      "postgres@sha256:64c8b0d31e4ab6ea665e8426ea406a94bf52de3c05b3a4a8bf713704eefa9328";

  private ContainerImages() {}
}
