package com.cafe.menuservice.menuitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.menuservice.category.Category;
import com.cafe.menuservice.category.CategoryService;
import com.cafe.menuservice.menuitem.dto.MenuItemRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuItemServiceTest {

  private static final Long ITEM_ID = 12L;
  private static final Long MISSING_ID = 99L;
  private static final Long CATEGORY_ID = 1L;
  private static final Long NEW_CATEGORY_ID = 2L;

  @Mock private MenuItemRepository menuItemRepository;
  @Mock private CategoryService categoryService;

  private MenuItemService menuItemService;

  @BeforeEach
  void setUp() {
    menuItemService = new MenuItemService(menuItemRepository, categoryService);
  }

  private Category category(Long id) {
    return Category.builder().id(id).name("Category " + id).displayOrder(1).active(true).build();
  }

  private MenuItem menuItem(Long id) {
    return menuItem(id, "Item " + id);
  }

  private MenuItem menuItem(Long id, String name) {
    return MenuItem.builder()
        .id(id)
        .category(category(CATEGORY_ID))
        .name(name)
        .price(BigDecimal.TEN)
        .available(true)
        .active(true)
        .build();
  }

  private MenuItemRequest request() {
    return new MenuItemRequest(
        NEW_CATEGORY_ID, "Latte", "Milky", BigDecimal.valueOf(50000), "latte.png", false, true);
  }

  @Test
  void search_delegatesToRepositoryWithBothFilters() {
    List<MenuItem> found = List.of(menuItem(ITEM_ID));
    when(menuItemRepository.search(CATEGORY_ID, true)).thenReturn(found);

    List<MenuItem> result = menuItemService.search(CATEGORY_ID, true);

    assertThat(result).isSameAs(found);
  }

  @Test
  void findById_found_returnsTheItem() {
    MenuItem item = menuItem(ITEM_ID);
    when(menuItemRepository.findByIdWithCategory(ITEM_ID)).thenReturn(Optional.of(item));

    assertThat(menuItemService.findById(ITEM_ID)).isSameAs(item);
  }

  @Test
  void findById_notFound_throwsResourceNotFoundException() {
    when(menuItemRepository.findByIdWithCategory(MISSING_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> menuItemService.findById(MISSING_ID))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("MenuItem not found: " + MISSING_ID);
  }

  @Test
  void findAllById_returnsWhateverTheRepositoryFound() {
    MenuItem one = menuItem(1L);
    MenuItem two = menuItem(2L);
    when(menuItemRepository.findAllByIdWithCategory(List.of(1L, 2L))).thenReturn(List.of(one, two));

    List<MenuItem> result = menuItemService.findAllById(List.of(1L, 2L));

    assertThat(result).containsExactly(one, two);
  }

  /**
   * Unlike findById, a batch lookup silently omits any id not found rather than throwing - only the
   * caller is positioned to know whether a missing id is even an error.
   */
  @Test
  void findAllById_idNotFound_isSilentlyOmittedFromTheResult() {
    when(menuItemRepository.findAllByIdWithCategory(List.of(1L, MISSING_ID)))
        .thenReturn(List.of(menuItem(1L)));

    List<MenuItem> result = menuItemService.findAllById(List.of(1L, MISSING_ID));

    assertThat(result).hasSize(1);
  }

  @Test
  void create_loadsTheRequestedCategoryAndSavesAnItemBuiltFromTheRequest() {
    MenuItemRequest request = request();
    Category newCategory = category(NEW_CATEGORY_ID);
    when(categoryService.findById(NEW_CATEGORY_ID)).thenReturn(newCategory);
    ArgumentCaptor<MenuItem> captor = ArgumentCaptor.forClass(MenuItem.class);
    when(menuItemRepository.save(captor.capture()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    MenuItem result = menuItemService.create(request);

    MenuItem saved = captor.getValue();
    assertAll(
        () -> assertThat(result).isSameAs(saved),
        () -> assertThat(saved.getCategory()).isSameAs(newCategory),
        () -> assertThat(saved.getName()).isEqualTo(request.name()),
        () -> assertThat(saved.getDescription()).isEqualTo(request.description()),
        () -> assertThat(saved.getPrice()).isEqualTo(request.price()),
        () -> assertThat(saved.getImageUrl()).isEqualTo(request.imageUrl()),
        () -> assertThat(saved.isAvailable()).isEqualTo(request.available()),
        () -> assertThat(saved.isActive()).isEqualTo(request.active()));
  }

  @Test
  void update_found_overwritesEveryFieldIncludingCategoryThenSaves() {
    MenuItem existing = menuItem(ITEM_ID);
    MenuItemRequest request = request();
    Category newCategory = category(NEW_CATEGORY_ID);
    when(menuItemRepository.findByIdWithCategory(ITEM_ID)).thenReturn(Optional.of(existing));
    when(categoryService.findById(NEW_CATEGORY_ID)).thenReturn(newCategory);
    when(menuItemRepository.save(existing)).thenReturn(existing);

    MenuItem result = menuItemService.update(ITEM_ID, request);

    assertAll(
        () -> assertThat(result).isSameAs(existing),
        () -> assertThat(existing.getCategory()).isSameAs(newCategory),
        () -> assertThat(existing.getName()).isEqualTo(request.name()),
        () -> assertThat(existing.getDescription()).isEqualTo(request.description()),
        () -> assertThat(existing.getPrice()).isEqualTo(request.price()),
        () -> assertThat(existing.getImageUrl()).isEqualTo(request.imageUrl()),
        () -> assertThat(existing.isAvailable()).isEqualTo(request.available()),
        () -> assertThat(existing.isActive()).isEqualTo(request.active()));
  }

  @Test
  void update_notFound_throwsAndNeverTouchesCategoryOrSaves() {
    when(menuItemRepository.findByIdWithCategory(MISSING_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> menuItemService.update(MISSING_ID, request()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("MenuItem not found: " + MISSING_ID);

    verify(categoryService, never()).findById(any());
    verify(menuItemRepository, never()).save(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void updateAvailability_found_setsOnlyAvailabilityAndLeavesOtherFieldsUntouched(
      boolean available) {
    MenuItem existing = menuItem(ITEM_ID);
    String originalName = existing.getName();
    BigDecimal originalPrice = existing.getPrice();
    when(menuItemRepository.findByIdWithCategory(ITEM_ID)).thenReturn(Optional.of(existing));
    when(menuItemRepository.save(existing)).thenReturn(existing);

    MenuItem result = menuItemService.updateAvailability(ITEM_ID, available);

    assertAll(
        () -> assertThat(result).isSameAs(existing),
        () -> assertThat(existing.isAvailable()).isEqualTo(available),
        () -> assertThat(existing.getName()).isEqualTo(originalName),
        () -> assertThat(existing.getPrice()).isEqualTo(originalPrice));
  }

  @Test
  void updateAvailability_notFound_throwsAndNeverSaves() {
    when(menuItemRepository.findByIdWithCategory(MISSING_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> menuItemService.updateAvailability(MISSING_ID, true))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("MenuItem not found: " + MISSING_ID);

    verify(menuItemRepository, never()).save(any());
  }
}
