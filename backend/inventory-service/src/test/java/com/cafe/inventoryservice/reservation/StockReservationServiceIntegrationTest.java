package com.cafe.inventoryservice.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.cafe.common.event.InventoryStockCommitReply;
import com.cafe.common.event.InventoryStockReservationReply;
import com.cafe.common.event.OrderLineItem;
import com.cafe.inventoryservice.ingredient.Ingredient;
import com.cafe.inventoryservice.ingredient.IngredientRepository;
import com.cafe.inventoryservice.recipe.MenuItemIngredient;
import com.cafe.inventoryservice.recipe.MenuItemIngredientRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real Postgres container, since {@link StockReservationService}'s entire
 * "all-or-nothing under concurrency" guarantee depends on {@link
 * IngredientRepository#findAllByIdForUpdate}'s {@code PESSIMISTIC_WRITE} lock genuinely serializing
 * concurrent transactions - a Mockito-mocked repository (the existing {@code
 * StockReservationServiceTest}, which this class doesn't replace) can only prove the arithmetic is
 * correct for one call at a time, never that two real concurrent calls can't both win.
 *
 * <p>{@link StockReservationService} is brought in via {@link Import} rather than {@code new}'d
 * directly, so it's instantiated by Spring and genuinely wrapped in a {@code @Transactional} proxy
 * - constructing it directly (as a Mockito-style unit test would) bypasses Spring AOP entirely,
 * meaning {@code @Transactional} would have no effect and the row lock would never actually span
 * the whole method.
 *
 * <p>Every test method here runs with {@code @Transactional(propagation =
 * Propagation.NOT_SUPPORTED)} - {@code @DataJpaTest}'s own per-test transaction is suspended, since
 * its default rollback-wrapping would otherwise be the only "transaction" in play, making it
 * impossible to run two genuinely concurrent ones. Both sides of each race are driven explicitly
 * through {@link TransactionTemplate} instead. Because the rollback-wrapping is suspended, the rows
 * each test creates are genuinely committed to the shared Postgres container, so every test deletes
 * them explicitly in a {@code finally} block.
 *
 * <p>Tagged {@code testcontainers} so environments without a reachable Docker daemon (e.g. this
 * service's own Docker image build stage - see its Dockerfile) can exclude just this class and
 * still run every other test.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(StockReservationService.class)
class StockReservationServiceIntegrationTest {

  private static final Logger log =
      LoggerFactory.getLogger(StockReservationServiceIntegrationTest.class);
  private static final long AWAIT_SECONDS = 5;

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

  @Autowired private IngredientRepository ingredientRepository;
  @Autowired private MenuItemIngredientRepository menuItemIngredientRepository;
  @Autowired private StockMovementRepository stockMovementRepository;
  @Autowired private StockReservationService service;
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

  /**
   * Two different orders race for 60 units each of an ingredient that only has 100 in stock -
   * demand (120) exceeds supply (100), so exactly one must be told there isn't enough. A broken
   * lock's failure signature here isn't "both get 120 reserved" (the code always writes an absolute
   * value, {@code reservedQuantity.add(required)}, not a DB-side atomic increment) - it's both
   * replies reporting {@code success=true} while the persisted value lands on a plausible-looking
   * 60, silently masking that two holds were approved for stock that only covers one.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void reserve_underRealConcurrency_allowsOnlyOneOrderToHoldTheLimitedStock() throws Exception {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    Long menuItemId = 500L;
    Long ingredientId =
        transactionTemplate.execute(
            status -> {
              Ingredient ingredient =
                  ingredientRepository.save(
                      Ingredient.builder()
                          .name("Milk")
                          .unit("ml")
                          .currentStock(new BigDecimal("100.000"))
                          .minStock(BigDecimal.ZERO)
                          .reservedQuantity(BigDecimal.ZERO)
                          .active(true)
                          .build());
              menuItemIngredientRepository.save(
                  MenuItemIngredient.builder()
                      .menuItemId(menuItemId)
                      .ingredient(ingredient)
                      .quantityRequired(new BigDecimal("60.000"))
                      .build());
              return ingredient.getId();
            });
    executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    List<OrderLineItem> items = List.of(new OrderLineItem(menuItemId, 1));

    try {
      Future<InventoryStockReservationReply> first =
          executor.submit(reserveTask(1001L, items, barrier));
      Future<InventoryStockReservationReply> second =
          executor.submit(reserveTask(1002L, items, barrier));

      InventoryStockReservationReply firstReply = first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      InventoryStockReservationReply secondReply = second.get(AWAIT_SECONDS, TimeUnit.SECONDS);

      long successes =
          Stream.of(firstReply, secondReply)
              .filter(InventoryStockReservationReply::success)
              .count();
      Ingredient after =
          transactionTemplate.execute(
              status -> ingredientRepository.findById(ingredientId).orElseThrow());

      assertAll(
          () ->
              assertThat(successes)
                  .as("exactly one of two orders competing for 60+60 > 100 units must win")
                  .isEqualTo(1),
          () ->
              Stream.of(firstReply, secondReply)
                  .filter(reply -> !reply.success())
                  .forEach(
                      reply -> assertThat(reply.reason()).containsIgnoringCase("insufficient")),
          () -> assertThat(after.getReservedQuantity()).isEqualByComparingTo("60.000"));
    } finally {
      Long finalIngredientId = ingredientId;
      try {
        transactionTemplate.executeWithoutResult(
            status -> {
              menuItemIngredientRepository.deleteByMenuItemId(menuItemId);
              ingredientRepository.deleteById(finalIngredientId);
            });
      } catch (Exception e) {
        log.warn("Failed to delete test ingredient {} during cleanup", finalIngredientId, e);
      }
    }
  }

  /**
   * Two different orders, each already holding its own valid 60-unit reservation on the same
   * ingredient, commit at the same time. {@link IngredientRepository#findAllByIdForUpdate}'s lock
   * is the only thing preventing a classic lost update here - {@code Ingredient} has no
   * {@code @Version} field, so without the pessimistic lock, both commits would read the same stale
   * snapshot and the second write would silently clobber the first's deduction.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void commit_underRealConcurrency_appliesBothDeductionsWithoutLosingEither() throws Exception {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    Long menuItemId = 501L;
    Long ingredientId =
        transactionTemplate.execute(
            status -> {
              Ingredient ingredient =
                  ingredientRepository.save(
                      Ingredient.builder()
                          .name("Coffee Beans")
                          .unit("g")
                          .currentStock(new BigDecimal("200.000"))
                          .minStock(BigDecimal.ZERO)
                          .reservedQuantity(new BigDecimal("120.000")) // two prior 60-unit holds
                          .active(true)
                          .build());
              menuItemIngredientRepository.save(
                  MenuItemIngredient.builder()
                      .menuItemId(menuItemId)
                      .ingredient(ingredient)
                      .quantityRequired(new BigDecimal("60.000"))
                      .build());
              return ingredient.getId();
            });
    executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    List<OrderLineItem> items = List.of(new OrderLineItem(menuItemId, 1));

    try {
      Future<InventoryStockCommitReply> first = executor.submit(commitTask(2001L, items, barrier));
      Future<InventoryStockCommitReply> second = executor.submit(commitTask(2002L, items, barrier));

      InventoryStockCommitReply firstReply = first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      InventoryStockCommitReply secondReply = second.get(AWAIT_SECONDS, TimeUnit.SECONDS);

      Ingredient after =
          transactionTemplate.execute(
              status -> ingredientRepository.findById(ingredientId).orElseThrow());

      assertAll(
          () -> assertThat(firstReply.success()).isTrue(),
          () -> assertThat(secondReply.success()).isTrue(),
          () -> assertThat(after.getCurrentStock()).isEqualByComparingTo("80.000"),
          () -> assertThat(after.getReservedQuantity()).isEqualByComparingTo("0.000"),
          () ->
              assertThat(
                      stockMovementRepository.findAllByIngredientIdOrderByCreatedAtDesc(
                          ingredientId))
                  .hasSize(2)
                  .allSatisfy(
                      movement ->
                          assertThat(movement.getChangeAmount()).isEqualByComparingTo("-60.000")));
    } finally {
      Long finalIngredientId = ingredientId;
      try {
        transactionTemplate.executeWithoutResult(
            status -> {
              stockMovementRepository.deleteAll(
                  stockMovementRepository.findAllByIngredientIdOrderByCreatedAtDesc(
                      finalIngredientId));
              menuItemIngredientRepository.deleteByMenuItemId(menuItemId);
              ingredientRepository.deleteById(finalIngredientId);
            });
      } catch (Exception e) {
        log.warn("Failed to delete test ingredient {} during cleanup", finalIngredientId, e);
      }
    }
  }

  /**
   * Two different orders, each holding its own valid 60-unit reservation on the same ingredient,
   * get released (e.g. both cancelled) at the same time. Same lost-update risk as {@link
   * #commit_underRealConcurrency_appliesBothDeductionsWithoutLosingEither}, but on {@code
   * reservedQuantity} alone - a broken lock here would silently leave stock held that no order is
   * using anymore.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void release_underRealConcurrency_releasesBothHoldsWithoutLosingEither() throws Exception {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    Long menuItemId = 502L;
    Long ingredientId =
        transactionTemplate.execute(
            status -> {
              Ingredient ingredient =
                  ingredientRepository.save(
                      Ingredient.builder()
                          .name("Sugar")
                          .unit("g")
                          .currentStock(new BigDecimal("200.000"))
                          .minStock(BigDecimal.ZERO)
                          .reservedQuantity(new BigDecimal("120.000")) // two prior 60-unit holds
                          .active(true)
                          .build());
              menuItemIngredientRepository.save(
                  MenuItemIngredient.builder()
                      .menuItemId(menuItemId)
                      .ingredient(ingredient)
                      .quantityRequired(new BigDecimal("60.000"))
                      .build());
              return ingredient.getId();
            });
    executor = Executors.newFixedThreadPool(2);
    CyclicBarrier barrier = new CyclicBarrier(2);
    List<OrderLineItem> items = List.of(new OrderLineItem(menuItemId, 1));

    try {
      Future<?> first = executor.submit(releaseTask(3001L, items, barrier));
      Future<?> second = executor.submit(releaseTask(3002L, items, barrier));

      first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      second.get(AWAIT_SECONDS, TimeUnit.SECONDS);

      Ingredient after =
          transactionTemplate.execute(
              status -> ingredientRepository.findById(ingredientId).orElseThrow());

      assertAll(
          () -> assertThat(after.getReservedQuantity()).isEqualByComparingTo("0.000"),
          () -> assertThat(after.getCurrentStock()).isEqualByComparingTo("200.000"));
    } finally {
      Long finalIngredientId = ingredientId;
      try {
        transactionTemplate.executeWithoutResult(
            status -> {
              menuItemIngredientRepository.deleteByMenuItemId(menuItemId);
              ingredientRepository.deleteById(finalIngredientId);
            });
      } catch (Exception e) {
        log.warn("Failed to delete test ingredient {} during cleanup", finalIngredientId, e);
      }
    }
  }

  private Callable<InventoryStockReservationReply> reserveTask(
      Long orderId, List<OrderLineItem> items, CyclicBarrier barrier) {
    return () -> {
      barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
      return service.reserve(orderId, items);
    };
  }

  private Callable<InventoryStockCommitReply> commitTask(
      Long orderId, List<OrderLineItem> items, CyclicBarrier barrier) {
    return () -> {
      barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
      return service.commit(orderId, items);
    };
  }

  private Callable<Void> releaseTask(
      Long orderId, List<OrderLineItem> items, CyclicBarrier barrier) {
    return () -> {
      barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS);
      service.release(orderId, items);
      return null;
    };
  }
}
