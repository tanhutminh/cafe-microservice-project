import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoModule } from '@jsverse/transloco';
import { Ingredient, IngredientRequest } from '../../core/models/ingredient.model';
import { closeIfChanged } from '../../shared/dialog-utils';
import { trimFields } from '../../shared/form-utils';

export interface IngredientDialogData {
  ingredient: Ingredient | null;
}

@Component({
  selector: 'app-ingredient-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    TranslocoModule
  ],
  templateUrl: './ingredient-dialog.html'
})
export class IngredientDialog {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<IngredientDialog>);
  readonly data = inject<IngredientDialogData>(MAT_DIALOG_DATA);

  readonly isEdit = this.data.ingredient !== null;

  private readonly originalRequest: IngredientRequest | null = this.data.ingredient
    ? {
        name: this.data.ingredient.name,
        unit: this.data.ingredient.unit,
        minStock: this.data.ingredient.minStock,
        active: this.data.ingredient.active
      }
    : null;

  readonly form = this.fb.group({
    name: [this.data.ingredient?.name ?? '', Validators.required],
    unit: [this.data.ingredient?.unit ?? '', Validators.required],
    minStock: [this.data.ingredient?.minStock ?? 0, [Validators.required, Validators.min(0)]],
    active: [this.data.ingredient?.active ?? true]
  });

  save(): void {
    if (this.form.invalid) {
      return;
    }
    const current = trimFields(this.form.getRawValue() as IngredientRequest, ['name', 'unit']);
    closeIfChanged(this.dialogRef, this.originalRequest, current);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
