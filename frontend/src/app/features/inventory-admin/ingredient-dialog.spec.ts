import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TestBed } from '@angular/core/testing';
import { Ingredient } from '../../core/models/ingredient.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { IngredientDialog, IngredientDialogData } from './ingredient-dialog';

describe('IngredientDialog', () => {
  const existingIngredient: Ingredient = {
    id: 1,
    name: 'Coffee beans',
    unit: 'g',
    currentStock: 100,
    minStock: 10,
    reservedQuantity: 0,
    lowStock: false,
    active: true
  };

  let dialogRef: { close: ReturnType<typeof vi.fn> };

  function createFixture(data: IngredientDialogData) {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data }
      ]
    });
    return TestBed.createComponent(IngredientDialog);
  }

  function createComponent(data: IngredientDialogData) {
    return createFixture(data).componentInstance;
  }

  it('pre-fills the form from the existing ingredient when editing', () => {
    const component = createComponent({ ingredient: existingIngredient });

    expect(component.isEdit).toBe(true);
    expect(component.form.getRawValue()).toEqual({ name: 'Coffee beans', unit: 'g', minStock: 10, active: true });
  });

  it('defaults to a blank, active form when creating', () => {
    const component = createComponent({ ingredient: null });

    expect(component.isEdit).toBe(false);
    expect(component.form.getRawValue()).toEqual({ name: '', unit: '', minStock: 0, active: true });
  });

  it('save() does nothing while the form is invalid', () => {
    const component = createComponent({ ingredient: null });
    component.form.patchValue({ name: '' });

    component.save();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('save() closes with no result when nothing changed', () => {
    const component = createComponent({ ingredient: existingIngredient });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() trims name/unit before comparing and closing', () => {
    const component = createComponent({ ingredient: existingIngredient });
    component.form.patchValue({ name: '  Coffee beans  ', unit: '  g  ' });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() closes with the trimmed request when a real change is made', () => {
    const component = createComponent({ ingredient: existingIngredient });
    component.form.patchValue({ name: '  Arabica beans  ' });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({ name: 'Arabica beans', unit: 'g', minStock: 10, active: true });
  });

  it('save() always closes with the request when creating (no baseline)', () => {
    const component = createComponent({ ingredient: null });
    component.form.patchValue({ name: 'Milk', unit: 'ml', minStock: 5 });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({ name: 'Milk', unit: 'ml', minStock: 5, active: true });
  });

  it('cancel() closes with no result', () => {
    const component = createComponent({ ingredient: existingIngredient });

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it('renders the edit form layout without error', () => {
    const editFixture = createFixture({ ingredient: existingIngredient });
    expect(() => editFixture.detectChanges()).not.toThrow();
  });

  it('renders the create form layout without error', () => {
    const createFixtureInstance = createFixture({ ingredient: null });
    expect(() => createFixtureInstance.detectChanges()).not.toThrow();
  });
});
