package com.cafe.inventoryservice.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real Postgres container (not an embedded substitute), since {@link
 * IngredientRepository#findAllByIdForUpdate}'s {@code PESSIMISTIC_WRITE} lock is a concurrency
 * guarantee a Mockito-mocked repository (this codebase's usual test style) is structurally unable
 * to exercise - a mock only proves the method was called, never that a real database lock actually
 * blocks a concurrent transaction, which is the entire "all-or-nothing under concurrency" property
 * this method exists to provide.
 *
 * <p>Tagged {@code testcontainers} so environments without a reachable Docker daemon (e.g. this
 * service's own Docker image build stage - see its Dockerfile) can exclude just this class and
 * still run every other test.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IngredientRepositoryTest {

  private static final Logger log = LoggerFactory.getLogger(IngredientRepositoryTest.class);
  private static final long AWAIT_SECONDS = 5;

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

  @Autowired private TestEntityManager entityManager;
  @Autowired private IngredientRepository ingredientRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private ExecutorService executor;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (executor != null) {
      executor.shutdownNow();
      if (!executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Executor did not terminate within {}s of shutdownNow()", AWAIT_SECONDS);
      }
    }
  }

  private Ingredient ingredient(String name, boolean active) {
    Ingredient ingredient =
        Ingredient.builder()
            .name(name)
            .unit("g")
            .currentStock(BigDecimal.TEN)
            .minStock(BigDecimal.ZERO)
            .reservedQuantity(BigDecimal.ZERO)
            .active(active)
            .build();
    return entityManager.persistFlushFind(ingredient);
  }

  @Test
  void findAllByIdForUpdate_returnsExactlyTheMatchingIngredients() {
    Ingredient flour = ingredient("Flour", true);
    Ingredient sugar = ingredient("Sugar", true);
    ingredient("Milk", true); // not requested - must not appear in the result

    List<Ingredient> result =
        ingredientRepository.findAllByIdForUpdate(List.of(flour.getId(), sugar.getId(), 999_999L));

    assertThat(result)
        .extracting(Ingredient::getId)
        .containsExactlyInAnyOrder(flour.getId(), sugar.getId());
  }

  @Test
  void findAllByIdForUpdate_returnsEmptyWhenNoIdMatches() {
    assertThat(ingredientRepository.findAllByIdForUpdate(List.of(999_999L))).isEmpty();
  }

  /**
   * Documents the method's current, known-incomplete contract - it locks by id alone, with no
   * {@code active} filter - rather than silently assuming the opposite. Whether {@code
   * active=false} should block a reservation is a separate, already-tracked decision, not something
   * this test endorses or fixes.
   */
  @Test
  void findAllByIdForUpdate_includesInactiveIngredients_becauseTheMethodDoesNotFilterByActive() {
    Ingredient discontinued = ingredient("Discontinued Syrup", false);

    List<Ingredient> result =
        ingredientRepository.findAllByIdForUpdate(List.of(discontinued.getId()));

    assertThat(result).extracting(Ingredient::getId).containsExactly(discontinued.getId());
  }

  /**
   * Proves the row lock is real, not just requested: a second transaction trying to acquire the
   * same {@code PESSIMISTIC_WRITE} lock while the first still holds it must fail fast (asserted via
   * {@code jakarta.persistence.lock.timeout=0}, i.e. {@code NOWAIT}, so the test is deterministic
   * rather than depending on wall-clock timing), and must succeed again once the first transaction
   * releases it. {@code @DataJpaTest}'s own per-test transaction is suspended for this method (its
   * default rollback-wrapping would otherwise be the only "transaction" in play, making it
   * impossible to run two genuinely concurrent ones) - both sides are driven explicitly through
   * {@link TransactionTemplate} instead.
   *
   * <p>Because the rollback-wrapping is suspended, the row this test creates is genuinely committed
   * to the shared Postgres container, so it's deleted explicitly in {@code finally}.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void findAllByIdForUpdate_blocksAConcurrentLockOnTheSameRow() throws Exception {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    Long ingredientId =
        transactionTemplate.execute(status -> ingredient("Locked Ingredient", true).getId());
    executor = Executors.newSingleThreadExecutor();
    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    Future<?> holder = null;

    try {
      holder =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        ingredientRepository.findAllByIdForUpdate(List.of(ingredientId));
                        lockAcquired.countDown();
                        awaitQuietly(releaseLock);
                      }));

      boolean acquired = lockAcquired.await(AWAIT_SECONDS, TimeUnit.SECONDS);
      if (!acquired && holder.isDone()) {
        // The background transaction failed before it could count down the latch - surface its
        // real exception instead of the uninformative "expected true, was false" below.
        holder.get();
      }
      assertThat(acquired).isTrue();

      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () ->
              assertThatThrownBy(
                      () ->
                          transactionTemplate.executeWithoutResult(
                              status ->
                                  entityManager
                                      .getEntityManager()
                                      .find(
                                          Ingredient.class,
                                          ingredientId,
                                          LockModeType.PESSIMISTIC_WRITE,
                                          Map.of("jakarta.persistence.lock.timeout", 0))))
                  .isInstanceOfAny(PessimisticLockException.class, LockTimeoutException.class),
          "jakarta.persistence.lock.timeout=0 should make Hibernate/Postgres fail fast (NOWAIT) "
              + "instead of blocking; a timeout here means that translation broke and this "
              + "assertion would otherwise deadlock against the background transaction");

      releaseLock.countDown();
      holder.get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertThatCode(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> ingredientRepository.findAllByIdForUpdate(List.of(ingredientId))))
          .doesNotThrowAnyException();
    } finally {
      // Idempotent - lets the background transaction finish even if an assertion above already
      // failed before reaching the line that does this.
      releaseLock.countDown();
      if (holder != null) {
        try {
          holder.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          // A cleanup-phase problem here must not mask the original assertion failure above.
          log.warn("Background locking transaction did not finish cleanly during cleanup", e);
        }
      }
      try {
        transactionTemplate.executeWithoutResult(
            status -> ingredientRepository.deleteById(ingredientId));
      } catch (Exception e) {
        log.warn("Failed to delete test ingredient {} during cleanup", ingredientId, e);
      }
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
