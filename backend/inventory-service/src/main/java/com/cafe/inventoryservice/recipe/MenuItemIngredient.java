package com.cafe.inventoryservice.recipe;

import com.cafe.inventoryservice.ingredient.Ingredient;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One recipe line: how much of an ingredient a menu item consumes per unit sold.
 * menuItemId is a loose reference into menu-service's own database (plan section 8) —
 * there is no real FK across service boundaries, only ingredientId is a true FK here.
 */
@Entity
@Table(name = "menu_item_ingredients")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MenuItemIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingredient_id", nullable = false)
    private Ingredient ingredient;

    @Column(name = "quantity_required", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityRequired;
}
