import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { TranslocoModule } from '@jsverse/transloco';
import { Ingredient } from '../../core/models/ingredient.model';

export interface StockInDialogData {
  ingredient: Ingredient;
}

@Component({
  selector: 'app-stock-in-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, TranslocoModule],
  templateUrl: './stock-in-dialog.html'
})
export class StockInDialog {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<StockInDialog>);
  readonly data = inject<StockInDialogData>(MAT_DIALOG_DATA);

  readonly form = this.fb.group({
    quantity: [0, [Validators.required, Validators.min(0.001)]]
  });

  save(): void {
    if (this.form.invalid) {
      return;
    }
    this.dialogRef.close(this.form.getRawValue().quantity);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
