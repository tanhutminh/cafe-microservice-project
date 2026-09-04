package com.cafe.orderservice.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for order-service's real-Postgres (Testcontainers) repository tests - subclasses get real
 * database semantics for JPQL/SQL text a Mockito-mocked repository can't exercise (bulk
 * {@code @Modifying} updates, pessimistic locks, {@code NOT EXISTS} subqueries, and similar).
 *
 * <p>The container is started once in a static initializer and shared by every subclass for the
 * lifetime of the JVM (Testcontainers' documented "singleton container" pattern), rather than one
 * container per test class. This deliberately does NOT use
 * {@code @Container}/{@code @Testcontainers}: that annotation pair ties a container's stop to the
 * declaring test class's own {@code afterAll} lifecycle, which would leave the container dead for
 * the next subclass to reuse.
 *
 * <p>Tagged {@code testcontainers} so environments without a reachable Docker daemon (e.g. this
 * service's own Docker image build stage - see its Dockerfile) can exclude every subclass and still
 * run every other test.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractPostgresRepositoryTest {

  @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

  static {
    postgres.start();
  }
}
