import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { InventoryApiService } from '../../core/inventory/inventory-api.service';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { Ingredient } from '../../core/models/ingredient.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { StockMovement } from '../../core/models/stock-movement.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { InventoryAdmin } from './inventory-admin';

describe('InventoryAdmin', () => {
  const ingredient: Ingredient = {
    id: 1,
    name: 'Coffee beans',
    unit: 'g',
    currentStock: 100,
    minStock: 10,
    reservedQuantity: 30,
    lowStock: false,
    active: true
  };
  const menuItem: MenuItem = {
    id: 7,
    categoryId: 1,
    categoryName: 'Coffee',
    name: 'Latte',
    description: null,
    price: 45000,
    imageUrl: null,
    available: true,
    active: true
  };

  let inventoryApi: {
    listIngredients: ReturnType<typeof vi.fn>;
    updateIngredient: ReturnType<typeof vi.fn>;
    createIngredient: ReturnType<typeof vi.fn>;
    deleteIngredient: ReturnType<typeof vi.fn>;
    stockIn: ReturnType<typeof vi.fn>;
    listMovements: ReturnType<typeof vi.fn>;
    getRecipe: ReturnType<typeof vi.fn>;
  };
  let menuApi: { listMenuItems: ReturnType<typeof vi.fn> };
  // MatDialog is resolved as a real instance regardless of a DI override (its own module
  // re-provides it in the component's injector chain) - spy on the shared prototype method instead.
  let dialogOpen: ReturnType<typeof vi.spyOn>;

  function createFixture() {
    return TestBed.createComponent(InventoryAdmin);
  }

  function createComponent() {
    return createFixture().componentInstance;
  }

  beforeEach(() => {
    inventoryApi = {
      listIngredients: vi.fn().mockReturnValue(of([ingredient])),
      updateIngredient: vi.fn().mockReturnValue(of(ingredient)),
      createIngredient: vi.fn().mockReturnValue(of(ingredient)),
      deleteIngredient: vi.fn().mockReturnValue(of(undefined)),
      stockIn: vi.fn().mockReturnValue(of(ingredient)),
      listMovements: vi.fn().mockReturnValue(of([])),
      getRecipe: vi.fn().mockReturnValue(of([]))
    };
    menuApi = { listMenuItems: vi.fn().mockReturnValue(of([menuItem])) };
    dialogOpen = vi.spyOn(MatDialog.prototype, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);

    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        provideRouter([]),
        { provide: InventoryApiService, useValue: inventoryApi },
        { provide: MenuApiService, useValue: menuApi }
      ]
    });
  });

  afterEach(() => {
    dialogOpen.mockRestore();
  });

  it('loads ingredients and menu items on init', () => {
    const component = createComponent();

    expect(inventoryApi.listIngredients).toHaveBeenCalled();
    expect(menuApi.listMenuItems).toHaveBeenCalled();
    expect(component.ingredients()).toEqual([ingredient]);
    expect(component.menuItems()).toEqual([menuItem]);
  });

  it('availableOf() subtracts reservedQuantity from currentStock', () => {
    const component = createComponent();

    expect(component.availableOf(ingredient)).toBe(70);
  });

  it('openIngredientDialog() reloads the list after a create/edit result', () => {
    dialogOpen.mockReturnValue({ afterClosed: () => of({ name: 'Milk', unit: 'ml', minStock: 5, active: true }) } as never);
    const component = createComponent();
    inventoryApi.listIngredients.mockClear();

    component.openIngredientDialog(ingredient);

    expect(dialogOpen).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: { ingredient } }));
    expect(inventoryApi.updateIngredient).toHaveBeenCalledWith(1, { name: 'Milk', unit: 'ml', minStock: 5, active: true });
    expect(inventoryApi.listIngredients).toHaveBeenCalled();
  });

  it('openIngredientDialog() calls createIngredient when opened for a new ingredient', () => {
    dialogOpen.mockReturnValue({ afterClosed: () => of({ name: 'Milk', unit: 'ml', minStock: 5, active: true }) } as never);
    const component = createComponent();

    component.openIngredientDialog(null);

    expect(inventoryApi.createIngredient).toHaveBeenCalledWith({ name: 'Milk', unit: 'ml', minStock: 5, active: true });
    expect(inventoryApi.updateIngredient).not.toHaveBeenCalled();
  });

  it('openIngredientDialog() does not call the API when the dialog closes with no result', () => {
    const component = createComponent();

    component.openIngredientDialog(ingredient);

    expect(inventoryApi.updateIngredient).not.toHaveBeenCalled();
    expect(inventoryApi.createIngredient).not.toHaveBeenCalled();
  });

  it('deleteIngredient() stops propagation, deletes, and reloads', () => {
    const component = createComponent();
    const event = { stopPropagation: vi.fn() } as unknown as Event;
    inventoryApi.listIngredients.mockClear();

    component.deleteIngredient(ingredient, event);

    expect(event.stopPropagation).toHaveBeenCalled();
    expect(inventoryApi.deleteIngredient).toHaveBeenCalledWith(1);
    expect(inventoryApi.listIngredients).toHaveBeenCalled();
  });

  it('openStockInDialog() stocks in the returned quantity and reloads', () => {
    dialogOpen.mockReturnValue({ afterClosed: () => of(25) } as never);
    const component = createComponent();
    const event = { stopPropagation: vi.fn() } as unknown as Event;
    inventoryApi.listIngredients.mockClear();

    component.openStockInDialog(ingredient, event);

    expect(event.stopPropagation).toHaveBeenCalled();
    expect(inventoryApi.stockIn).toHaveBeenCalledWith(1, { quantity: 25 });
    expect(inventoryApi.listIngredients).toHaveBeenCalled();
  });

  it('openStockInDialog() does nothing when the dialog is cancelled', () => {
    const component = createComponent();
    const event = { stopPropagation: vi.fn() } as unknown as Event;

    component.openStockInDialog(ingredient, event);

    expect(inventoryApi.stockIn).not.toHaveBeenCalled();
  });

  it('openMovementsDialog() fetches movements then opens the dialog with them', () => {
    const movements: StockMovement[] = [
      { id: 1, changeAmount: 10, reason: 'STOCK_IN', referenceId: null, createdAt: '2026-08-01T00:00:00Z' }
    ];
    inventoryApi.listMovements.mockReturnValue(of(movements));
    const component = createComponent();
    const event = { stopPropagation: vi.fn() } as unknown as Event;

    component.openMovementsDialog(ingredient, event);

    expect(event.stopPropagation).toHaveBeenCalled();
    expect(inventoryApi.listMovements).toHaveBeenCalledWith(1);
    expect(dialogOpen).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ data: { ingredientName: 'Coffee beans', movements } })
    );
  });

  it('selectMenuItemForRecipe() sets the selected menu item', () => {
    const component = createComponent();

    component.selectMenuItemForRecipe(menuItem);

    expect(component.selectedMenuItem()).toEqual(menuItem);
  });

  it('renders the Ingredients tab, then the Recipes tab once a menu item is picked', () => {
    const fixture = createFixture();

    expect(() => fixture.detectChanges()).not.toThrow();

    fixture.componentInstance.selectedTabIndex.set(1);
    fixture.componentInstance.selectMenuItemForRecipe(menuItem);
    expect(() => fixture.detectChanges()).not.toThrow();

    expect(inventoryApi.getRecipe).toHaveBeenCalledWith(menuItem.id);
  });
});
