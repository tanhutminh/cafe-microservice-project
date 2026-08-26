package com.cafe.menuservice.menuitem;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.menuservice.category.Category;
import com.cafe.menuservice.category.CategoryService;
import com.cafe.menuservice.menuitem.dto.MenuItemRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuItemService {

  private final MenuItemRepository menuItemRepository;
  private final CategoryService categoryService;

  public MenuItemService(MenuItemRepository menuItemRepository, CategoryService categoryService) {
    this.menuItemRepository = menuItemRepository;
    this.categoryService = categoryService;
  }

  public List<MenuItem> search(Long categoryId, Boolean available) {
    return menuItemRepository.search(categoryId, available);
  }

  public MenuItem findById(Long id) {
    return menuItemRepository
        .findByIdWithCategory(id)
        .orElseThrow(() -> ResourceNotFoundException.of("MenuItem", id));
  }

  /**
   * Batch equivalent of {@link #findById(Long)} - a single query covering every id, instead of one
   * query per id. Unlike {@link #findById(Long)}, silently omits any id not found rather than
   * throwing - the caller is the only one positioned to know whether a missing id is even an error.
   */
  public List<MenuItem> findAllById(List<Long> ids) {
    return menuItemRepository.findAllByIdWithCategory(ids);
  }

  @Transactional
  public MenuItem create(MenuItemRequest request) {
    Category category = categoryService.findById(request.categoryId());
    MenuItem item =
        MenuItem.builder()
            .category(category)
            .name(request.name())
            .description(request.description())
            .price(request.price())
            .imageUrl(request.imageUrl())
            .available(request.available())
            .active(request.active())
            .build();
    return menuItemRepository.save(item);
  }

  @Transactional
  public MenuItem update(Long id, MenuItemRequest request) {
    MenuItem item = findById(id);
    Category category = categoryService.findById(request.categoryId());
    item.setCategory(category);
    item.setName(request.name());
    item.setDescription(request.description());
    item.setPrice(request.price());
    item.setImageUrl(request.imageUrl());
    item.setAvailable(request.available());
    item.setActive(request.active());
    return menuItemRepository.save(item);
  }

  @Transactional
  public MenuItem updateAvailability(Long id, boolean available) {
    MenuItem item = findById(id);
    item.setAvailable(available);
    return menuItemRepository.save(item);
  }
}
