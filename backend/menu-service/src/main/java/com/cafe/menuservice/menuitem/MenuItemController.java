package com.cafe.menuservice.menuitem;

import com.cafe.menuservice.menuitem.dto.MenuItemRequest;
import com.cafe.menuservice.menuitem.dto.MenuItemResponse;
import com.cafe.menuservice.menuitem.dto.UpdateAvailabilityRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/menu-items")
public class MenuItemController {

    private final MenuItemService menuItemService;

    public MenuItemController(MenuItemService menuItemService) {
        this.menuItemService = menuItemService;
    }

    @GetMapping
    public List<MenuItemResponse> search(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Boolean available
    ) {
        return menuItemService.search(categoryId, available).stream().map(MenuItemResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItemResponse create(@Valid @RequestBody MenuItemRequest request) {
        return MenuItemResponse.from(menuItemService.create(request));
    }

    @PutMapping("/{id}")
    public MenuItemResponse update(@PathVariable Long id, @Valid @RequestBody MenuItemRequest request) {
        return MenuItemResponse.from(menuItemService.update(id, request));
    }

    @PatchMapping("/{id}/availability")
    public MenuItemResponse updateAvailability(@PathVariable Long id, @Valid @RequestBody UpdateAvailabilityRequest request) {
        return MenuItemResponse.from(menuItemService.updateAvailability(id, request.available()));
    }
}
