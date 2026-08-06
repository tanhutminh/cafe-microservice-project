import { Component, inject, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { TranslocoModule } from '@jsverse/transloco';
import { Category } from '../../core/models/category.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { RecipeEditor } from '../../shared/recipe-editor/recipe-editor';

export interface MenuItemDialogData {
  item: MenuItem | null;
  categories: Category[];
}

@Component({
  selector: 'app-menu-item-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    TranslocoModule,
    RecipeEditor
  ],
  templateUrl: './menu-item-dialog.html',
  styleUrl: './menu-item-dialog.scss'
})
export class MenuItemDialog {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<MenuItemDialog>);
  readonly data = inject<MenuItemDialogData>(MAT_DIALOG_DATA);

  readonly isEdit = this.data.item !== null;
  private readonly recipeEditor = viewChild(RecipeEditor);

  readonly form = this.fb.group({
    categoryId: [this.data.item?.categoryId ?? this.data.categories[0]?.id ?? null, Validators.required],
    name: [this.data.item?.name ?? '', Validators.required],
    description: [this.data.item?.description ?? ''],
    price: [this.data.item?.price ?? 0, [Validators.required, Validators.min(0)]],
    imageUrl: [this.data.item?.imageUrl ?? ''],
    available: [this.data.item?.available ?? true],
    active: [this.data.item?.active ?? true]
  });

  save(): void {
    if (this.form.invalid) {
      return;
    }
    this.recipeEditor()?.saveRecipe();
    this.dialogRef.close(this.form.getRawValue());
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
