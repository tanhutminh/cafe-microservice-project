package com.cafe.inventoryservice.recipe;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuItemIngredientRepository extends JpaRepository<MenuItemIngredient, Long> {

  /**
   * Safe to {@code JOIN FETCH} {@code ingredient} here - unlike {@link #findAllByMenuItemIdIn},
   * this method is never called in the same transaction as a {@code PESSIMISTIC_WRITE}-locked read
   * of the same row.
   */
  @Query(
      "SELECT r FROM MenuItemIngredient r JOIN FETCH r.ingredient WHERE r.menuItemId = :menuItemId")
  List<MenuItemIngredient> findAllByMenuItemIdWithIngredient(@Param("menuItemId") Long menuItemId);

  /**
   * Deliberately does not {@code JOIN FETCH} {@code ingredient} - the returned association stays an
   * uninitialized lazy proxy. Pre-loading it here would place an initialized instance into the
   * persistence context, and a later {@code PESSIMISTIC_WRITE}-locked read of the same row within
   * the same transaction would then silently return that stale initialized instance instead of the
   * freshly locked data.
   */
  List<MenuItemIngredient> findAllByMenuItemIdIn(List<Long> menuItemIds);

  void deleteByMenuItemId(Long menuItemId);
}
