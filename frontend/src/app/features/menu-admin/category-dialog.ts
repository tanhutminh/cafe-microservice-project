import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoModule } from '@jsverse/transloco';
import { Category } from '../../core/models/category.model';

export interface CategoryDialogData {
  category: Category | null;
}

@Component({
  selector: 'app-category-dialog',
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
  templateUrl: './category-dialog.html'
})
export class CategoryDialog {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<CategoryDialog>);
  readonly data = inject<CategoryDialogData>(MAT_DIALOG_DATA);

  readonly isEdit = this.data.category !== null;

  readonly form = this.fb.group({
    name: [this.data.category?.name ?? '', Validators.required],
    displayOrder: [this.data.category?.displayOrder ?? 0, Validators.required],
    active: [this.data.category?.active ?? true]
  });

  save(): void {
    if (this.form.invalid) {
      return;
    }
    this.dialogRef.close(this.form.getRawValue());
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
