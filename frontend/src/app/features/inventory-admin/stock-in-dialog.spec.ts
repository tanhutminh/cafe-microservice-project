import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TestBed } from '@angular/core/testing';
import { Ingredient } from '../../core/models/ingredient.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { StockInDialog, StockInDialogData } from './stock-in-dialog';

describe('StockInDialog', () => {
  const ingredient: Ingredient = {
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

  function createFixture(data: StockInDialogData) {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data }
      ]
    });
    return TestBed.createComponent(StockInDialog);
  }

  function createComponent(data: StockInDialogData) {
    return createFixture(data).componentInstance;
  }

  it('starts with a zero quantity', () => {
    const component = createComponent({ ingredient });

    expect(component.form.getRawValue()).toEqual({ quantity: 0 });
  });

  it('save() does nothing when the quantity is not positive', () => {
    const component = createComponent({ ingredient });
    component.form.patchValue({ quantity: 0 });

    component.save();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('save() closes with the entered quantity when valid', () => {
    const component = createComponent({ ingredient });
    component.form.patchValue({ quantity: 25 });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(25);
  });

  it('cancel() closes with no result', () => {
    const component = createComponent({ ingredient });

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it('renders without error', () => {
    const fixture = createFixture({ ingredient });

    expect(() => fixture.detectChanges()).not.toThrow();
  });
});
