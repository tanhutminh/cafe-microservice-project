import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { InventoryApiService } from '../../core/inventory/inventory-api.service';
import { Category } from '../../core/models/category.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { RecipeEditor } from '../../shared/recipe-editor/recipe-editor';
import { MenuItemDialog, MenuItemDialogData } from './menu-item-dialog';

describe('MenuItemDialog', () => {
  const categories: Category[] = [{ id: 1, name: 'Coffee', displayOrder: 1, active: true }];
  const existingItem: MenuItem = {
    id: 1,
    categoryId: 1,
    categoryName: 'Coffee',
    name: 'Latte',
    description: 'Milky coffee',
    price: 45000,
    imageUrl: null,
    available: true,
    active: true
  };

  let dialogRef: { close: ReturnType<typeof vi.fn> };

  function createComponent(data: MenuItemDialogData) {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
        {
          provide: InventoryApiService,
          useValue: {
            listIngredients: vi.fn().mockReturnValue(of([])),
            getRecipe: vi.fn().mockReturnValue(of([])),
            replaceRecipe: vi.fn().mockReturnValue(of([]))
          }
        }
      ]
    });
    return TestBed.createComponent(MenuItemDialog);
  }

  it('pre-fills the form from the existing item when editing', () => {
    const component = createComponent({ item: existingItem, categories }).componentInstance;

    expect(component.isEdit).toBe(true);
    expect(component.form.getRawValue()).toEqual({
      categoryId: 1,
      name: 'Latte',
      description: 'Milky coffee',
      price: 45000,
      imageUrl: '',
      available: true,
      active: true
    });
  });

  it('defaults to the first category and blank fields when creating', () => {
    const component = createComponent({ item: null, categories }).componentInstance;

    expect(component.isEdit).toBe(false);
    expect(component.form.getRawValue()).toEqual({
      categoryId: 1,
      name: '',
      description: '',
      price: 0,
      imageUrl: '',
      available: true,
      active: true
    });
  });

  it('save() does nothing while the form is invalid', () => {
    const component = createComponent({ item: null, categories }).componentInstance;
    component.form.patchValue({ name: '' });

    component.save();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('save() closes with no result when nothing changed', () => {
    const component = createComponent({ item: existingItem, categories }).componentInstance;

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() treats null description/imageUrl as equal to the form\'s empty-string baseline', () => {
    const itemWithNulls: MenuItem = { ...existingItem, description: null, imageUrl: null };
    const component = createComponent({ item: itemWithNulls, categories }).componentInstance;

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() trims text fields before comparing and closing', () => {
    const component = createComponent({ item: existingItem, categories }).componentInstance;
    component.form.patchValue({ name: '  Latte  ', description: '  Milky coffee  ' });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() closes with the trimmed request when a real change is made', () => {
    const component = createComponent({ item: existingItem, categories }).componentInstance;
    component.form.patchValue({ name: '  Iced Latte  ' });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({
      categoryId: 1,
      name: 'Iced Latte',
      description: 'Milky coffee',
      price: 45000,
      imageUrl: '',
      available: true,
      active: true
    });
  });

  it('save() always closes with the request when creating (no baseline)', () => {
    const component = createComponent({ item: null, categories }).componentInstance;
    component.form.patchValue({ name: 'Mocha', price: 50000 });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({
      categoryId: 1,
      name: 'Mocha',
      description: '',
      price: 50000,
      imageUrl: '',
      available: true,
      active: true
    });
  });

  it('cancel() closes with no result', () => {
    const component = createComponent({ item: existingItem, categories }).componentInstance;

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it('save() also triggers the embedded recipe editor to save when editing an existing item', () => {
    const saveRecipeSpy = vi.spyOn(RecipeEditor.prototype, 'saveRecipe').mockImplementation(() => {});
    const fixture = createComponent({ item: existingItem, categories });
    fixture.detectChanges();

    fixture.componentInstance.save();

    expect(saveRecipeSpy).toHaveBeenCalled();
    saveRecipeSpy.mockRestore();
  });

  it('save() does not error when creating (no recipe editor mounted yet)', () => {
    const fixture = createComponent({ item: null, categories });
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ name: 'Mocha' });

    expect(() => fixture.componentInstance.save()).not.toThrow();
  });
});
