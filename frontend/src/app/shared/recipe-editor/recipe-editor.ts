import { Component, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslocoModule } from '@jsverse/transloco';
import { InventoryApiService } from '../../core/inventory/inventory-api.service';
import { Ingredient } from '../../core/models/ingredient.model';
import { RecipeItem, RecipeItemRequest } from '../../core/models/recipe-item.model';

@Component({
  selector: 'app-recipe-editor',
  standalone: true,
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    TranslocoModule
  ],
  templateUrl: './recipe-editor.html',
  styleUrl: './recipe-editor.scss'
})
export class RecipeEditor {
  private readonly inventoryApi = inject(InventoryApiService);

  readonly menuItemId = input.required<number>();

  readonly ingredients = signal<Ingredient[]>([]);
  readonly recipeLines = signal<RecipeItem[]>([]);

  constructor() {
    this.inventoryApi.listIngredients().subscribe((ingredients) => this.ingredients.set(ingredients));

    effect(() => {
      const id = this.menuItemId();
      this.inventoryApi.getRecipe(id).subscribe((lines) => this.recipeLines.set(lines));
    });
  }

  addRecipeLine(): void {
    const firstIngredient = this.ingredients()[0];
    if (!firstIngredient) {
      return;
    }
    this.recipeLines.update((lines) => [
      ...lines,
      {
        ingredientId: firstIngredient.id,
        ingredientName: firstIngredient.name,
        unit: firstIngredient.unit,
        quantityRequired: 0
      }
    ]);
  }

  removeRecipeLine(index: number): void {
    this.recipeLines.update((lines) => lines.filter((_, i) => i !== index));
  }

  onRecipeIngredientChange(index: number, ingredientId: number): void {
    const ingredient = this.ingredients().find((i) => i.id === ingredientId);
    if (!ingredient) {
      return;
    }
    this.recipeLines.update((lines) =>
      lines.map((line, i) =>
        i === index ? { ...line, ingredientId, ingredientName: ingredient.name, unit: ingredient.unit } : line
      )
    );
  }

  onRecipeQuantityChange(index: number, quantityRequired: number): void {
    this.recipeLines.update((lines) => lines.map((line, i) => (i === index ? { ...line, quantityRequired } : line)));
  }

  saveRecipe(): void {
    const lines: RecipeItemRequest[] = this.recipeLines().map((line) => ({
      ingredientId: line.ingredientId,
      quantityRequired: line.quantityRequired
    }));
    this.inventoryApi.replaceRecipe(this.menuItemId(), lines).subscribe((updated) => this.recipeLines.set(updated));
  }
}
