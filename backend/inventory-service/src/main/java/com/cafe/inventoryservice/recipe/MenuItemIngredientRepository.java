package com.cafe.inventoryservice.recipe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuItemIngredientRepository extends JpaRepository<MenuItemIngredient, Long> {

    @Query("SELECT r FROM MenuItemIngredient r JOIN FETCH r.ingredient WHERE r.menuItemId = :menuItemId")
    List<MenuItemIngredient> findByMenuItemIdWithIngredient(@Param("menuItemId") Long menuItemId);

    @Query("SELECT r FROM MenuItemIngredient r JOIN FETCH r.ingredient WHERE r.menuItemId IN :menuItemIds")
    List<MenuItemIngredient> findByMenuItemIdInWithIngredient(@Param("menuItemIds") List<Long> menuItemIds);

    void deleteByMenuItemId(Long menuItemId);
}
