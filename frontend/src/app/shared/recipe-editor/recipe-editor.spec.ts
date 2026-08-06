import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of } from 'rxjs';
import { InventoryApiService } from '../../core/inventory/inventory-api.service';
import { Ingredient } from '../../core/models/ingredient.model';
import { RecipeItem } from '../../core/models/recipe-item.model';
import { RecipeEditor } from './recipe-editor';

describe('RecipeEditor', () => {
  const ingredients: Ingredient[] = [
    { id: 1, name: 'Coffee beans', unit: 'g', currentStock: 100, minStock: 10, reservedQuantity: 0, lowStock: false, active: true },
    { id: 2, name: 'Milk', unit: 'ml', currentStock: 50, minStock: 5, reservedQuantity: 0, lowStock: false, active: true }
  ];
  const recipeLines: RecipeItem[] = [{ ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 20 }];

  let inventoryApi: {
    listIngredients: ReturnType<typeof vi.fn>;
    getRecipe: ReturnType<typeof vi.fn>;
    replaceRecipe: ReturnType<typeof vi.fn>;
  };

  function createComponent(menuItemId = 1) {
    const fixture = TestBed.createComponent(RecipeEditor);
    fixture.componentRef.setInput('menuItemId', menuItemId);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    inventoryApi = {
      listIngredients: vi.fn().mockReturnValue(of(ingredients)),
      getRecipe: vi.fn().mockReturnValue(of(recipeLines)),
      replaceRecipe: vi.fn().mockReturnValue(of(recipeLines))
    };

    TestBed.configureTestingModule({
      imports: [
        RecipeEditor,
        TranslocoTestingModule.forRoot({
          langs: { en: {} },
          translocoConfig: { availableLangs: ['en'], defaultLang: 'en' }
        })
      ],
      providers: [{ provide: InventoryApiService, useValue: inventoryApi }]
    });
  });

  it('loads ingredients and the recipe for the given menu item on init', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;

    expect(inventoryApi.listIngredients).toHaveBeenCalled();
    expect(inventoryApi.getRecipe).toHaveBeenCalledWith(1);
    expect(component.ingredients()).toEqual(ingredients);
    expect(component.recipeLines()).toEqual(recipeLines);
  });

  it('reloads the recipe when menuItemId changes', () => {
    const fixture = createComponent(1);
    const otherLines: RecipeItem[] = [{ ingredientId: 2, ingredientName: 'Milk', unit: 'ml', quantityRequired: 100 }];
    inventoryApi.getRecipe.mockReturnValue(of(otherLines));

    fixture.componentRef.setInput('menuItemId', 2);
    fixture.detectChanges();

    expect(inventoryApi.getRecipe).toHaveBeenCalledWith(2);
    expect(fixture.componentInstance.recipeLines()).toEqual(otherLines);
  });

  it('addRecipeLine() appends a line defaulting to the first ingredient', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.recipeLines.set([]);

    component.addRecipeLine();

    expect(component.recipeLines()).toEqual([
      { ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 0 }
    ]);
  });

  it('addRecipeLine() is a no-op when there are no ingredients', () => {
    inventoryApi.listIngredients.mockReturnValue(of([]));
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.recipeLines.set([]);

    component.addRecipeLine();

    expect(component.recipeLines()).toEqual([]);
  });

  it('removeRecipeLine() removes the line at the given index', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.recipeLines.set([
      { ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 20 },
      { ingredientId: 2, ingredientName: 'Milk', unit: 'ml', quantityRequired: 10 }
    ]);

    component.removeRecipeLine(0);

    expect(component.recipeLines()).toEqual([{ ingredientId: 2, ingredientName: 'Milk', unit: 'ml', quantityRequired: 10 }]);
  });

  it('onRecipeIngredientChange() swaps the ingredient and re-derives name/unit', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.recipeLines.set([{ ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 20 }]);

    component.onRecipeIngredientChange(0, 2);

    expect(component.recipeLines()[0]).toEqual({
      ingredientId: 2,
      ingredientName: 'Milk',
      unit: 'ml',
      quantityRequired: 20
    });
  });

  it('onRecipeIngredientChange() is a no-op for an unknown ingredient id', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    const line = { ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 20 };
    component.recipeLines.set([line]);

    component.onRecipeIngredientChange(0, 999);

    expect(component.recipeLines()[0]).toEqual(line);
  });

  it('onRecipeQuantityChange() updates only the quantity of the given line', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.recipeLines.set([{ ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 20 }]);

    component.onRecipeQuantityChange(0, 30);

    expect(component.recipeLines()[0].quantityRequired).toBe(30);
  });

  it('saveRecipe() does not call the API when the lines are unchanged from what was loaded', () => {
    const fixture = createComponent(1);

    fixture.componentInstance.saveRecipe();

    expect(inventoryApi.replaceRecipe).not.toHaveBeenCalled();
  });

  it('saveRecipe() calls replaceRecipe with the current lines when changed', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.onRecipeQuantityChange(0, 99);

    component.saveRecipe();

    expect(inventoryApi.replaceRecipe).toHaveBeenCalledWith(1, [{ ingredientId: 1, quantityRequired: 99 }]);
  });

  it('saveRecipe() updates the baseline after saving, so a second identical save is a no-op', () => {
    const fixture = createComponent(1);
    const component = fixture.componentInstance;
    component.onRecipeQuantityChange(0, 99);
    inventoryApi.replaceRecipe.mockReturnValue(
      of([{ ingredientId: 1, ingredientName: 'Coffee beans', unit: 'g', quantityRequired: 99 }])
    );

    component.saveRecipe();
    inventoryApi.replaceRecipe.mockClear();
    component.saveRecipe();

    expect(inventoryApi.replaceRecipe).not.toHaveBeenCalled();
  });
});
