import { HarnessLoader } from '@angular/cdk/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatButtonHarness } from '@angular/material/button/testing';
import { MatChipHarness } from '@angular/material/chips/testing';
import { MATERIAL_ANIMATIONS } from '@angular/material/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSlideToggleHarness } from '@angular/material/slide-toggle/testing';
import { MatRowHarness } from '@angular/material/table/testing';
import { MatTabGroupHarness } from '@angular/material/tabs/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { Category, CategoryRequest } from '../../core/models/category.model';
import { MenuItem, MenuItemRequest } from '../../core/models/menu-item.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { CategoryDialog } from './category-dialog';
import { MenuAdmin } from './menu-admin';
import { MenuItemDialog } from './menu-item-dialog';

describe('MenuAdmin', () => {
  const category: Category = { id: 1, name: 'Coffee', displayOrder: 1, active: true };
  const menuItem: MenuItem = {
    id: 7,
    categoryId: 1,
    categoryName: 'Coffee',
    name: 'Latte',
    description: null,
    price: 45000,
    imageUrl: null,
    available: true,
    active: true,
  };
  const categoryRequest: CategoryRequest = { name: 'Tea', displayOrder: 2, active: true };
  const menuItemRequest: MenuItemRequest = {
    categoryId: 1,
    name: 'Mocha',
    description: null,
    price: 50000,
    imageUrl: null,
    available: true,
    active: true,
  };

  let menuApi: {
    listCategories: ReturnType<typeof vi.fn>;
    createCategory: ReturnType<typeof vi.fn>;
    updateCategory: ReturnType<typeof vi.fn>;
    deleteCategory: ReturnType<typeof vi.fn>;
    listMenuItems: ReturnType<typeof vi.fn>;
    createMenuItem: ReturnType<typeof vi.fn>;
    updateMenuItem: ReturnType<typeof vi.fn>;
    updateMenuItemAvailability: ReturnType<typeof vi.fn>;
  };
  // MatDialog is resolved as a real instance regardless of a DI override (its own module
  // re-provides it in the component's injector chain) - spy on the shared prototype method instead.
  let dialogOpen: ReturnType<typeof vi.spyOn>;

  function closeDialogWith(result: unknown): void {
    dialogOpen.mockReturnValue({ afterClosed: () => of(result) } as never);
  }

  /** Forgets the calls the component made on init, so a test only sees the ones it causes. */
  function forgetInitialLoad(): void {
    Object.values(menuApi).forEach((fn) => fn.mockClear());
  }

  /** Creates the component, then forgets its initial load. */
  function createComponent(): MenuAdmin {
    const component = TestBed.createComponent(MenuAdmin).componentInstance;
    forgetInitialLoad();
    return component;
  }

  function stopEvent(): Event {
    return { stopPropagation: vi.fn() } as unknown as Event;
  }

  beforeEach(() => {
    menuApi = {
      listCategories: vi.fn().mockReturnValue(of([category])),
      createCategory: vi.fn().mockReturnValue(of(category)),
      updateCategory: vi.fn().mockReturnValue(of(category)),
      deleteCategory: vi.fn().mockReturnValue(of(undefined)),
      listMenuItems: vi.fn().mockReturnValue(of([menuItem])),
      createMenuItem: vi.fn().mockReturnValue(of(menuItem)),
      updateMenuItem: vi.fn().mockReturnValue(of(menuItem)),
      updateMenuItemAvailability: vi.fn().mockReturnValue(of(menuItem)),
    };
    dialogOpen = vi
      .spyOn(MatDialog.prototype, 'open')
      .mockReturnValue({ afterClosed: () => of(undefined) } as never);

    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [provideRouter([]), { provide: MenuApiService, useValue: menuApi }],
    });
  });

  afterEach(() => {
    dialogOpen.mockRestore();
  });

  it('loads all categories and unfiltered menu items on init, on the Categories tab', () => {
    const component = TestBed.createComponent(MenuAdmin).componentInstance;

    expect(menuApi.listCategories).toHaveBeenCalled();
    expect(menuApi.listMenuItems).toHaveBeenCalledWith(undefined);
    expect(component.categories()).toEqual([category]);
    expect(component.menuItems()).toEqual([menuItem]);
    expect(component.selectedTabIndex()).toBe(0);
    expect(component.filterCategory()).toBeNull();
  });

  describe('category filter', () => {
    it('viewCategoryItems() switches to the Menu Items tab filtered to that category', () => {
      const component = createComponent();

      component.viewCategoryItems(category);

      expect(component.filterCategory()).toEqual(category);
      expect(component.selectedTabIndex()).toBe(1);
      expect(menuApi.listMenuItems).toHaveBeenCalledWith(1);
    });

    it('clearCategoryFilter() drops the filter and reloads all menu items', () => {
      const component = createComponent();
      component.viewCategoryItems(category);
      menuApi.listMenuItems.mockClear();

      component.clearCategoryFilter();

      expect(component.filterCategory()).toBeNull();
      expect(menuApi.listMenuItems).toHaveBeenCalledWith(undefined);
    });
  });

  describe('openCategoryDialog()', () => {
    it('creates a new category from the dialog result, then reloads categories and menu items', () => {
      closeDialogWith(categoryRequest);
      const component = createComponent();

      component.openCategoryDialog(null);

      expect(dialogOpen).toHaveBeenCalledWith(
        CategoryDialog,
        expect.objectContaining({ data: { category: null } }),
      );
      expect(menuApi.createCategory).toHaveBeenCalledWith(categoryRequest);
      expect(menuApi.updateCategory).not.toHaveBeenCalled();
      expect(menuApi.listCategories).toHaveBeenCalled();
      expect(menuApi.listMenuItems).toHaveBeenCalled();
    });

    it('updates an existing category from the dialog result', () => {
      closeDialogWith(categoryRequest);
      const component = createComponent();

      component.openCategoryDialog(category);

      expect(dialogOpen).toHaveBeenCalledWith(
        CategoryDialog,
        expect.objectContaining({ data: { category } }),
      );
      expect(menuApi.updateCategory).toHaveBeenCalledWith(1, categoryRequest);
      expect(menuApi.createCategory).not.toHaveBeenCalled();
    });

    it('saves nothing when the dialog closes without a result', () => {
      const component = createComponent();

      component.openCategoryDialog(category);

      expect(menuApi.createCategory).not.toHaveBeenCalled();
      expect(menuApi.updateCategory).not.toHaveBeenCalled();
      expect(menuApi.listCategories).not.toHaveBeenCalled();
    });

    it("stops the click from reaching the row, so it doesn't also open the category's items", () => {
      const component = createComponent();
      const event = stopEvent();

      component.openCategoryDialog(category, event);

      expect(event.stopPropagation).toHaveBeenCalled();
    });
  });

  it('deleteCategory() stops the row click, deletes and reloads the categories', () => {
    const component = createComponent();
    const event = stopEvent();

    component.deleteCategory(category, event);

    expect(event.stopPropagation).toHaveBeenCalled();
    expect(menuApi.deleteCategory).toHaveBeenCalledWith(1);
    expect(menuApi.listCategories).toHaveBeenCalled();
  });

  describe('openMenuItemDialog()', () => {
    it('passes the loaded categories and creates a new menu item from the result, then reloads the items', () => {
      closeDialogWith(menuItemRequest);
      const component = createComponent();

      component.openMenuItemDialog(null);

      expect(dialogOpen).toHaveBeenCalledWith(
        MenuItemDialog,
        expect.objectContaining({ data: { item: null, categories: [category] } }),
      );
      expect(menuApi.createMenuItem).toHaveBeenCalledWith(menuItemRequest);
      expect(menuApi.updateMenuItem).not.toHaveBeenCalled();
      expect(menuApi.listMenuItems).toHaveBeenCalled();
    });

    it('updates an existing menu item from the dialog result', () => {
      closeDialogWith(menuItemRequest);
      const component = createComponent();

      component.openMenuItemDialog(menuItem);

      expect(menuApi.updateMenuItem).toHaveBeenCalledWith(7, menuItemRequest);
      expect(menuApi.createMenuItem).not.toHaveBeenCalled();
    });

    it('saves nothing when the dialog closes without a result', () => {
      const component = createComponent();

      component.openMenuItemDialog(menuItem);

      expect(menuApi.createMenuItem).not.toHaveBeenCalled();
      expect(menuApi.updateMenuItem).not.toHaveBeenCalled();
      expect(menuApi.listMenuItems).not.toHaveBeenCalled();
    });
  });

  it.each([true, false])(
    'toggleAvailability() flips available=%s and reloads the menu items',
    (available) => {
      const component = createComponent();

      component.toggleAvailability({ ...menuItem, available });

      expect(menuApi.updateMenuItemAvailability).toHaveBeenCalledWith(7, !available);
      expect(menuApi.listMenuItems).toHaveBeenCalled();
    },
  );

  // Drives the rendered template through Angular Material's component harnesses, so these cases
  // also cover the template's bindings (row click, $event, [selectedIndex], [disabled], the chip).
  describe('rendered', () => {
    beforeEach(() => {
      // Material only attaches a newly selected tab's content once its animation ends, and jsdom
      // never fires the transition events that would end it.
      TestBed.configureTestingModule({
        providers: [{ provide: MATERIAL_ANIMATIONS, useValue: { animationsDisabled: true } }],
      });
    });

    /** Renders the component, then forgets its initial load. */
    async function render(): Promise<{ component: MenuAdmin; loader: HarnessLoader }> {
      const fixture = TestBed.createComponent(MenuAdmin);
      const loader = TestbedHarnessEnvironment.loader(fixture);
      await fixture.whenStable();
      forgetInitialLoad();
      return { component: fixture.componentInstance, loader };
    }

    async function clickCategoryRow(loader: HarnessLoader): Promise<void> {
      const row = await loader.getHarness(MatRowHarness.with({ selector: '.clickable-row' }));
      await (await row.host()).click();
    }

    async function selectTab(loader: HarnessLoader, label: string): Promise<void> {
      await (await loader.getHarness(MatTabGroupHarness)).selectTab({ label });
    }

    async function selectedTabLabel(loader: HarnessLoader): Promise<string> {
      const tabs = await loader.getHarness(MatTabGroupHarness);
      return (await tabs.getSelectedTab()).getLabel();
    }

    it('clicking a category row opens the Menu Items tab, filtered to that category', async () => {
      const { loader } = await render();

      await clickCategoryRow(loader);

      expect(await selectedTabLabel(loader)).toBe('menu.itemsTab');
      expect(menuApi.listMenuItems).toHaveBeenLastCalledWith(1);
    });

    it("the row's edit button opens the category dialog without also opening the category's items", async () => {
      const { component, loader } = await render();

      await (
        await loader.getHarness(
          MatButtonHarness.with({ ancestor: '.clickable-row', iconName: 'edit' }),
        )
      ).click();

      expect(dialogOpen).toHaveBeenCalledWith(
        CategoryDialog,
        expect.objectContaining({ data: { category } }),
      );
      expect(component.selectedTabIndex()).toBe(0);
      expect(component.filterCategory()).toBeNull();
      expect(menuApi.listMenuItems).not.toHaveBeenCalled();
    });

    it("the row's delete button deletes the category without also opening its items", async () => {
      const { component, loader } = await render();

      await (
        await loader.getHarness(
          MatButtonHarness.with({ ancestor: '.clickable-row', iconName: 'delete' }),
        )
      ).click();

      expect(menuApi.deleteCategory).toHaveBeenCalledWith(1);
      expect(component.selectedTabIndex()).toBe(0);
      expect(component.filterCategory()).toBeNull();
      expect(menuApi.listMenuItems).not.toHaveBeenCalled();
    });

    it('the New category button opens an empty category dialog', async () => {
      const { loader } = await render();

      await (await loader.getHarness(MatButtonHarness.with({ text: /menu\.newCategory/ }))).click();

      expect(dialogOpen).toHaveBeenCalledWith(
        CategoryDialog,
        expect.objectContaining({ data: { category: null } }),
      );
    });

    it('going back to the Categories tab lets a row click switch tabs again', async () => {
      const { component, loader } = await render();
      await clickCategoryRow(loader);

      await selectTab(loader, 'menu.categoriesTab');
      expect(component.selectedTabIndex()).toBe(0);

      await clickCategoryRow(loader);
      expect(await selectedTabLabel(loader)).toBe('menu.itemsTab');
    });

    it.each<[Category[], boolean]>([
      [[], true],
      [[category], false],
    ])('with categories %j the New item button is disabled: %s', async (categories, disabled) => {
      menuApi.listCategories.mockReturnValue(of(categories));
      const { loader } = await render();

      await selectTab(loader, 'menu.itemsTab');
      const newItem = await loader.getHarness(MatButtonHarness.with({ text: /menu\.newItem/ }));

      expect(await newItem.isDisabled()).toBe(disabled);
    });

    it('the New item button opens an empty menu item dialog with the loaded categories', async () => {
      const { loader } = await render();
      await selectTab(loader, 'menu.itemsTab');

      await (await loader.getHarness(MatButtonHarness.with({ text: /menu\.newItem/ }))).click();

      expect(dialogOpen).toHaveBeenCalledWith(
        MenuItemDialog,
        expect.objectContaining({ data: { item: null, categories: [category] } }),
      );
    });

    it("a menu item row's edit button opens the dialog for that item", async () => {
      const { loader } = await render();
      await selectTab(loader, 'menu.itemsTab');

      // The Categories tab's content is detached once another tab is selected, so the item row's
      // edit button is the only one left.
      const editButtons = await loader.getAllHarnesses(MatButtonHarness.with({ iconName: 'edit' }));
      expect(editButtons).toHaveLength(1);
      await editButtons[0].click();

      expect(dialogOpen).toHaveBeenCalledWith(
        MenuItemDialog,
        expect.objectContaining({ data: { item: menuItem, categories: [category] } }),
      );
    });

    it("removing the filter chip shows every category's menu items again", async () => {
      const { component, loader } = await render();
      await clickCategoryRow(loader);
      menuApi.listMenuItems.mockClear();

      await (await (await loader.getHarness(MatChipHarness)).getRemoveButton()).click();

      expect(component.filterCategory()).toBeNull();
      expect(menuApi.listMenuItems).toHaveBeenLastCalledWith(undefined);
      expect(await loader.getAllHarnesses(MatChipHarness)).toHaveLength(0);
    });

    it("flipping a menu item's availability toggle saves the flipped value", async () => {
      const { loader } = await render();
      await selectTab(loader, 'menu.itemsTab');

      await (await loader.getHarness(MatSlideToggleHarness)).toggle();

      expect(menuApi.updateMenuItemAvailability).toHaveBeenCalledWith(7, false);
    });
  });
});
