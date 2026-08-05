import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatTableModule } from '@angular/material/table';
import { TranslocoModule } from '@jsverse/transloco';
import { StockMovement } from '../../core/models/stock-movement.model';

export interface MovementsDialogData {
  ingredientName: string;
  movements: StockMovement[];
}

@Component({
  selector: 'app-movements-dialog',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatDialogModule, MatTableModule, TranslocoModule],
  templateUrl: './movements-dialog.html'
})
export class MovementsDialog {
  private readonly dialogRef = inject(MatDialogRef<MovementsDialog>);
  readonly data = inject<MovementsDialogData>(MAT_DIALOG_DATA);

  readonly movementColumns = ['changeAmount', 'reason', 'referenceId', 'createdAt'];

  close(): void {
    this.dialogRef.close();
  }
}
