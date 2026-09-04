package com.cafe.inventoryservice.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.cafe.inventoryservice.ingredient.Ingredient;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real Postgres container (not an embedded substitute) to pin down a Hibernate
 * persistence-context detail: {@link MenuItemIngredientRepository#findAllByMenuItemIdIn} must leave
 * the returned {@code ingredient} association as an uninitialized lazy proxy, never a pre-loaded
 * instance - a caller placing a pessimistic lock on that same row later in the same transaction
 * depends on Hibernate initializing that proxy from the locked read, not silently reusing an
 * already-initialized instance loaded here. A Mockito-mocked repository can't exercise this at all,
 * and even the real-Postgres concurrency tests elsewhere only catch a regression here indirectly
 * and slowly (via a lost-update failure), not with a direct, fast signal.
 *
 * <p>Tagged {@code testcontainers} so environments without a reachable Docker daemon (e.g. this
 * service's own Docker image build stage - see its Dockerfile) can exclude just this class and
 * still run every other test.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MenuItemIngredientRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

  @Autowired private TestEntityManager entityManager;
  @Autowired private MenuItemIngredientRepository menuItemIngredientRepository;

  private Ingredient ingredient(String name) {
    return entityManager.persistFlushFind(
        Ingredient.builder()
            .name(name)
            .unit("g")
            .currentStock(BigDecimal.TEN)
            .minStock(BigDecimal.ZERO)
            .reservedQuantity(BigDecimal.ZERO)
            .active(true)
            .build());
  }

  private MenuItemIngredient recipeLine(Long menuItemId, Ingredient ingredient) {
    return entityManager.persistFlushFind(
        MenuItemIngredient.builder()
            .menuItemId(menuItemId)
            .ingredient(ingredient)
            .quantityRequired(new BigDecimal("1.000"))
            .build());
  }

  @Test
  void findAllByMenuItemIdIn_returnsOnlyRowsForTheRequestedMenuItems() {
    Ingredient ingredient = ingredient("Flour");
    Long menuItemA = 1L;
    Long menuItemB = 2L;
    recipeLine(menuItemA, ingredient);
    recipeLine(menuItemB, ingredient);
    recipeLine(3L, ingredient); // not requested - must not appear in the result

    List<MenuItemIngredient> result =
        menuItemIngredientRepository.findAllByMenuItemIdIn(List.of(menuItemA, menuItemB));

    assertThat(result)
        .extracting(MenuItemIngredient::getMenuItemId)
        .containsExactlyInAnyOrder(menuItemA, menuItemB);
  }

  @Test
  void findAllByMenuItemIdIn_returnsEmptyWhenNoIdMatches() {
    assertThat(menuItemIngredientRepository.findAllByMenuItemIdIn(List.of(999_999L))).isEmpty();
  }

  @Test
  void findAllByMenuItemIdIn_leavesIngredientAsAnUninitializedProxy() {
    Ingredient ingredient = ingredient("Flour");
    Long menuItemId = 1L;
    recipeLine(menuItemId, ingredient);
    // Evicts the just-persisted Ingredient from the persistence context so the query below is the
    // first read of that row in this session - reusing the still-attached instance from setup
    // would make the assertion pass vacuously regardless of whether JOIN FETCH is present.
    entityManager.getEntityManager().clear();

    List<MenuItemIngredient> result =
        menuItemIngredientRepository.findAllByMenuItemIdIn(List.of(menuItemId));

    assertThat(result)
        .singleElement()
        .satisfies(line -> assertThat(Hibernate.isInitialized(line.getIngredient())).isFalse());
  }
}
