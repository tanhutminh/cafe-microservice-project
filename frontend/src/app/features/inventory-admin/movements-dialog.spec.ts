import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { TestBed } from '@angular/core/testing';
import { StockMovement } from '../../core/models/stock-movement.model';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { MovementsDialog, MovementsDialogData } from './movements-dialog';

describe('MovementsDialog', () => {
  const movements: StockMovement[] = [
    { id: 1, changeAmount: 10, reason: 'STOCK_IN', referenceId: null, createdAt: '2026-08-01T00:00:00Z' }
  ];

  let dialogRef: { close: ReturnType<typeof vi.fn> };

  function createFixture(data: MovementsDialogData) {
    dialogRef = { close: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data }
      ]
    });
    return TestBed.createComponent(MovementsDialog);
  }

  function createComponent(data: MovementsDialogData) {
    return createFixture(data).componentInstance;
  }

  it('exposes the injected data as-is', () => {
    const component = createComponent({ ingredientName: 'Coffee beans', movements });

    expect(component.data.ingredientName).toBe('Coffee beans');
    expect(component.data.movements).toEqual(movements);
  });

  it('exposes the expected table columns', () => {
    const component = createComponent({ ingredientName: 'Coffee beans', movements });

    expect(component.movementColumns).toEqual(['changeAmount', 'reason', 'referenceId', 'createdAt']);
  });

  it('close() closes the dialog with no result', () => {
    const component = createComponent({ ingredientName: 'Coffee beans', movements });

    component.close();

    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it('renders the populated layout without error', () => {
    const withData = createFixture({ ingredientName: 'Coffee beans', movements });
    expect(() => withData.detectChanges()).not.toThrow();
  });

  it('renders the empty-state layout without error', () => {
    const empty = createFixture({ ingredientName: 'Coffee beans', movements: [] });
    expect(() => empty.detectChanges()).not.toThrow();
  });
});
