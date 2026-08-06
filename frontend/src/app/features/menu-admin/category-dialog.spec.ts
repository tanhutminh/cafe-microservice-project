import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TestBed } from '@angular/core/testing';
import { Category } from '../../core/models/category.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { CategoryDialog, CategoryDialogData } from './category-dialog';

describe('CategoryDialog', () => {
  const existingCategory: Category = { id: 1, name: 'Coffee', displayOrder: 1, active: true };

  let dialogRef: { close: ReturnType<typeof vi.fn> };

  function createComponent(data: CategoryDialogData) {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data }
      ]
    });
    return TestBed.createComponent(CategoryDialog).componentInstance;
  }

  it('pre-fills the form from the existing category when editing', () => {
    const component = createComponent({ category: existingCategory });

    expect(component.isEdit).toBe(true);
    expect(component.form.getRawValue()).toEqual({ name: 'Coffee', displayOrder: 1, active: true });
  });

  it('defaults to a blank, active form when creating', () => {
    const component = createComponent({ category: null });

    expect(component.isEdit).toBe(false);
    expect(component.form.getRawValue()).toEqual({ name: '', displayOrder: 0, active: true });
  });

  it('save() does nothing while the form is invalid', () => {
    const component = createComponent({ category: null });
    component.form.patchValue({ name: '' });

    component.save();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('save() closes with no result when nothing changed', () => {
    const component = createComponent({ category: existingCategory });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() treats a whitespace-only edit as unchanged after trimming', () => {
    const component = createComponent({ category: existingCategory });
    component.form.patchValue({ name: '  Coffee  ' });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('save() closes with the trimmed request when a real change is made', () => {
    const component = createComponent({ category: existingCategory });
    component.form.patchValue({ name: '  Cold Coffee  ' });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({ name: 'Cold Coffee', displayOrder: 1, active: true });
  });

  it('save() always closes with the request when creating (no baseline)', () => {
    const component = createComponent({ category: null });
    component.form.patchValue({ name: 'Tea', displayOrder: 2 });

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({ name: 'Tea', displayOrder: 2, active: true });
  });

  it('cancel() closes with no result', () => {
    const component = createComponent({ category: existingCategory });

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith();
  });
});
