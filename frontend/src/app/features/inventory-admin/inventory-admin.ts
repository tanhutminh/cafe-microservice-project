import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { InventoryApiService } from '../../core/inventory/inventory-api.service';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { Ingredient, IngredientRequest, StockInRequest } from '../../core/models/ingredient.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { LanguageSwitcher } from '../../shared/language-switcher/language-switcher';
import { RecipeEditor } from '../../shared/recipe-editor/recipe-editor';
import { IngredientDialog, IngredientDialogData } from './ingredient-dialog';
import { MovementsDialog, MovementsDialogData } from './movements-dialog';
import { StockInDialog, StockInDialogData } from './stock-in-dialog';

const INGREDIENTS_TAB_INDEX = 0;
const RECIPES_TAB_INDEX = 1;

@Component({
  selector: 'app-inventory-admin',
  standalone: true,
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatTabsModule,
    MatToolbarModule,
    MatTooltipModule,
    TranslocoModule,
    RouterLink,
    LanguageSwitcher,
    RecipeEditor
  ],
  templateUrl: './inventory-admin.html',
  styleUrl: './inventory-admin.scss'
})
export class InventoryAdmin {
  private readonly inventoryApi = inject(InventoryApiService);
  private readonly menuApi = inject(MenuApiService);
  private readonly dialog = inject(MatDialog);

  readonly ingredients = signal<Ingredient[]>([]);
  readonly menuItems = signal<MenuItem[]>([]);
  readonly selectedTabIndex = signal(INGREDIENTS_TAB_INDEX);
  readonly selectedMenuItem = signal<MenuItem | null>(null);

  readonly ingredientColumns = [
    'name',
    'unit',
    'currentStock',
    'reservedQuantity',
    'available',
    'minStock',
    'lowStock',
    'active',
    'actions'
  ];

  constructor() {
    this.reloadIngredients();
    this.reloadMenuItems();
  }

  private reloadIngredients(): void {
    this.inventoryApi.listIngredients().subscribe((ingredients) => this.ingredients.set(ingredients));
  }

  private reloadMenuItems(): void {
    this.menuApi.listMenuItems().subscribe((items) => this.menuItems.set(items));
  }

  availableOf(ingredient: Ingredient): number {
    return ingredient.currentStock - ingredient.reservedQuantity;
  }

  openIngredientDialog(ingredient: Ingredient | null): void {
    const ref = this.dialog.open<IngredientDialog, IngredientDialogData, IngredientRequest>(IngredientDialog, {
      width: '400px',
      data: { ingredient }
    });
    ref.afterClosed().subscribe((result) => {
      if (!result) {
        return;
      }
      const save$ = ingredient
        ? this.inventoryApi.updateIngredient(ingredient.id, result)
        : this.inventoryApi.createIngredient(result);
      save$.subscribe(() => this.reloadIngredients());
    });
  }

  deleteIngredient(ingredient: Ingredient, event: Event): void {
    event.stopPropagation();
    this.inventoryApi.deleteIngredient(ingredient.id).subscribe(() => this.reloadIngredients());
  }

  openStockInDialog(ingredient: Ingredient, event: Event): void {
    event.stopPropagation();
    const ref = this.dialog.open<StockInDialog, StockInDialogData, number>(StockInDialog, {
      width: '360px',
      data: { ingredient }
    });
    ref.afterClosed().subscribe((quantity) => {
      if (quantity == null) {
        return;
      }
      const request: StockInRequest = { quantity };
      this.inventoryApi.stockIn(ingredient.id, request).subscribe(() => this.reloadIngredients());
    });
  }

  openMovementsDialog(ingredient: Ingredient, event: Event): void {
    event.stopPropagation();
    this.inventoryApi.listMovements(ingredient.id).subscribe((movements) => {
      this.dialog.open<MovementsDialog, MovementsDialogData>(MovementsDialog, {
        width: '520px',
        data: { ingredientName: ingredient.name, movements }
      });
    });
  }

  selectMenuItemForRecipe(item: MenuItem): void {
    this.selectedMenuItem.set(item);
  }
}
