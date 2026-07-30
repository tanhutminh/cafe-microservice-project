package com.cafe.menuservice.menuitem;

import com.cafe.menuservice.menuitem.dto.MenuItemRequest;
import com.cafe.menuservice.menuitem.dto.MenuItemResponse;
import com.cafe.menuservice.menuitem.dto.UpdateAvailabilityRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/menu-items")
@Tag(name = "Menu Items", description = "Sellable items, each belonging to a category")
public class MenuItemController {

    private final MenuItemService menuItemService;

    public MenuItemController(MenuItemService menuItemService) {
        this.menuItemService = menuItemService;
    }

    @GetMapping
    @Operation(summary = "Search menu items, optionally filtered by category and/or availability")
    public List<MenuItemResponse> search(
            @Parameter(description = "Only return items in this category", example = "1") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "Only return items with this availability", example = "true") @RequestParam(required = false) Boolean available
    ) {
        return menuItemService.search(categoryId, available).stream().map(MenuItemResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single menu item by id")
    public MenuItemResponse findById(@Parameter(description = "The menu item's id", example = "12") @PathVariable Long id) {
        return MenuItemResponse.from(menuItemService.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a menu item (ADMIN only)")
    public MenuItemResponse create(@Valid @RequestBody MenuItemRequest request) {
        return MenuItemResponse.from(menuItemService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a menu item (ADMIN only)")
    public MenuItemResponse update(@Parameter(description = "The menu item's id", example = "12") @PathVariable Long id, @Valid @RequestBody MenuItemRequest request) {
        return MenuItemResponse.from(menuItemService.update(id, request));
    }

    @PatchMapping("/{id}/availability")
    @Operation(
            summary = "86 an item (ADMIN or CASHIER)",
            description = "Toggles availability without deleting the item. \"86\" is F&B slang for "
                    + "pulling something off what can currently be sold - e.g. the kitchen runs out "
                    + "of an ingredient mid-shift and the counter needs to stop taking orders for it "
                    + "right away, without losing the item's menu entry, price, or recipe."
    )
    public MenuItemResponse updateAvailability(@Parameter(description = "The menu item's id", example = "12") @PathVariable Long id, @Valid @RequestBody UpdateAvailabilityRequest request) {
        return MenuItemResponse.from(menuItemService.updateAvailability(id, request.available()));
    }
}
