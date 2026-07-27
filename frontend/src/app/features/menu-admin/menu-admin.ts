import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { MatToolbarModule } from '@angular/material/toolbar';
import { TranslocoModule } from '@jsverse/transloco';
import { RouterLink } from '@angular/router';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { Category, CategoryRequest } from '../../core/models/category.model';
import { MenuItem, MenuItemRequest } from '../../core/models/menu-item.model';
import { LanguageSwitcher } from '../../shared/language-switcher/language-switcher';
import { CategoryDialog, CategoryDialogData } from './category-dialog';
import { MenuItemDialog, MenuItemDialogData } from './menu-item-dialog';

const CATEGORIES_TAB_INDEX = 0;
const ITEMS_TAB_INDEX = 1;

@Component({
  selector: 'app-menu-admin',
  standalone: true,
  imports: [
    MatButtonModule,
    MatChipsModule,
    MatDialogModule,
    MatIconModule,
    MatSlideToggleModule,
    MatTableModule,
    MatTabsModule,
    MatToolbarModule,
    TranslocoModule,
    RouterLink,
    LanguageSwitcher
  ],
  templateUrl: './menu-admin.html',
  styleUrl: './menu-admin.scss'
})
export class MenuAdmin {
  private readonly menuApi = inject(MenuApiService);
  private readonly dialog = inject(MatDialog);

  readonly categories = signal<Category[]>([]);
  readonly menuItems = signal<MenuItem[]>([]);
  readonly selectedTabIndex = signal(CATEGORIES_TAB_INDEX);
  readonly filterCategory = signal<Category | null>(null);

  readonly categoryColumns = ['name', 'displayOrder', 'active', 'actions'];
  readonly itemColumns = ['name', 'categoryName', 'price', 'available', 'active', 'actions'];

  constructor() {
    this.reloadCategories();
    this.reloadMenuItems();
  }

  private reloadCategories(): void {
    this.menuApi.listCategories().subscribe((categories) => this.categories.set(categories));
  }

  private reloadMenuItems(): void {
    this.menuApi.listMenuItems(this.filterCategory()?.id).subscribe((items) => this.menuItems.set(items));
  }

  /** Row click on the Categories tab — jump to Menu Items filtered to that category. */
  viewCategoryItems(category: Category): void {
    this.filterCategory.set(category);
    this.selectedTabIndex.set(ITEMS_TAB_INDEX);
    this.reloadMenuItems();
  }

  clearCategoryFilter(): void {
    this.filterCategory.set(null);
    this.reloadMenuItems();
  }

  openCategoryDialog(category: Category | null, event?: Event): void {
    event?.stopPropagation();
    const ref = this.dialog.open<CategoryDialog, CategoryDialogData, CategoryRequest>(CategoryDialog, {
      width: '400px',
      data: { category }
    });
    ref.afterClosed().subscribe((result) => {
      if (!result) {
        return;
      }
      const save$ = category
        ? this.menuApi.updateCategory(category.id, result)
        : this.menuApi.createCategory(result);
      save$.subscribe(() => {
        this.reloadCategories();
        this.reloadMenuItems();
      });
    });
  }

  deleteCategory(category: Category, event: Event): void {
    event.stopPropagation();
    this.menuApi.deleteCategory(category.id).subscribe(() => this.reloadCategories());
  }

  openMenuItemDialog(item: MenuItem | null): void {
    const ref = this.dialog.open<MenuItemDialog, MenuItemDialogData, MenuItemRequest>(MenuItemDialog, {
      width: '480px',
      data: { item, categories: this.categories() }
    });
    ref.afterClosed().subscribe((result) => {
      if (!result) {
        return;
      }
      const save$ = item ? this.menuApi.updateMenuItem(item.id, result) : this.menuApi.createMenuItem(result);
      save$.subscribe(() => this.reloadMenuItems());
    });
  }

  toggleAvailability(item: MenuItem): void {
    this.menuApi.updateMenuItemAvailability(item.id, !item.available).subscribe(() => this.reloadMenuItems());
  }
}
