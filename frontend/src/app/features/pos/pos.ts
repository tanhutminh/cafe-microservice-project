import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { Category } from '../../core/models/category.model';
import { DiningTable } from '../../core/models/dining-table.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { DraftItem, Order, OrderItem } from '../../core/models/order.model';
import { OrderApiService } from '../../core/order/order-api.service';
import { LanguageSwitcher } from '../../shared/language-switcher/language-switcher';

const POLL_INTERVAL_MS = 1000;
const POLL_MAX_ATTEMPTS = 10;

@Component({
  selector: 'app-pos',
  standalone: true,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatToolbarModule,
    RouterLink,
    TranslocoModule,
    LanguageSwitcher,
  ],
  templateUrl: './pos.html',
  styleUrl: './pos.scss',
})
export class Pos {
  private readonly orderApi = inject(OrderApiService);
  private readonly menuApi = inject(MenuApiService);

  readonly tables = signal<DiningTable[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly menuItems = signal<MenuItem[]>([]);
  readonly selectedTable = signal<DiningTable | null>(null);
  readonly currentOrder = signal<Order | null>(null);
  readonly draftItems = signal<DraftItem[]>([]);
  readonly checkingOut = signal(false);
  readonly movingTable = signal(false);

  constructor() {
    this.reloadTables();
    this.menuApi.listCategories().subscribe((categories) => this.categories.set(categories));
    this.menuApi
      .listMenuItems()
      .subscribe((items) => this.menuItems.set(items.filter((item) => item.available)));
  }

  private reloadTables(): void {
    this.orderApi.listTables().subscribe((tables) => this.tables.set(tables));
  }

  itemsByCategory(categoryId: number): MenuItem[] {
    return this.menuItems().filter((item) => item.categoryId === categoryId);
  }

  /** Items can be picked (as a local draft, not yet persisted) whenever there's no order yet or it's still OPEN. */
  canEdit(): boolean {
    const order = this.currentOrder();
    return !order || order.status === 'OPEN';
  }

  /** Enabled once there's something to submit and it actually differs from what the order already holds. */
  confirmEnabled(): boolean {
    const items = this.draftItems();
    if (items.length === 0) {
      return false;
    }
    const order = this.currentOrder();
    return !order || this.draftDiffersFromOrder(items, order.items);
  }

  grandTotal(): number {
    if (this.canEdit()) {
      return this.draftItems().reduce((sum, item) => sum + item.price * item.quantity, 0);
    }
    return this.currentOrder()?.grandTotal ?? 0;
  }

  private draftDiffersFromOrder(draft: DraftItem[], orderItems: OrderItem[]): boolean {
    if (draft.length !== orderItems.length) {
      return true;
    }
    const quantityByMenuItemId = new Map(draft.map((item) => [item.menuItemId, item.quantity]));
    return orderItems.some((item) => quantityByMenuItemId.get(item.menuItemId) !== item.quantity);
  }

  private toDraftItems(items: OrderItem[]): DraftItem[] {
    return items.map((item) => ({
      menuItemId: item.menuItemId,
      name: item.name,
      price: item.price,
      quantity: item.quantity,
    }));
  }

  selectTable(table: DiningTable): void {
    if (this.movingTable()) {
      if (table.status === 'AVAILABLE') {
        this.moveToTable(table);
      }
      return;
    }
    this.selectedTable.set(table);
    this.currentOrder.set(null);
    this.draftItems.set([]);
    if (table.status === 'AVAILABLE') {
      this.orderApi.occupyTable(table.id).subscribe(() => this.reloadTables());
    } else {
      // A 404 here means the table is OCCUPIED but nothing was ever confirmed on it (staff picked
      // items and closed the panel, or reloaded mid-draft) - not an error, just an empty cart to build.
      this.orderApi.getCurrentOrderForTable(table.id).subscribe({
        next: (order) => {
          this.currentOrder.set(order);
          this.draftItems.set(this.toDraftItems(order.items));
        },
        error: () => undefined,
      });
    }
  }

  closeOrderPanel(): void {
    const table = this.selectedTable();
    const hadNoOrder = table && !this.currentOrder();
    this.selectedTable.set(null);
    this.currentOrder.set(null);
    this.draftItems.set([]);
    this.movingTable.set(false);
    if (hadNoOrder) {
      // Nothing was ever confirmed on this table - closing the panel abandons the draft, so free
      // the table back up rather than leaving it OCCUPIED with nothing to show for it.
      this.orderApi.releaseTable(table.id).subscribe(() => this.reloadTables());
    }
  }

  startMoveTable(): void {
    this.movingTable.set(true);
  }

  cancelMoveTable(): void {
    this.movingTable.set(false);
  }

  private moveToTable(table: DiningTable): void {
    const order = this.currentOrder();
    if (!order) {
      this.movingTable.set(false);
      return;
    }
    this.orderApi.moveTable(order.id, { tableId: table.id }).subscribe((updated) => {
      this.currentOrder.set(updated);
      this.selectedTable.set(table);
      this.movingTable.set(false);
      this.reloadTables();
    });
  }

  /** Staff marks the table empty independent of payment status (pay-first-then-dine is allowed here). */
  releaseTable(): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.orderApi.releaseTable(order.tableId).subscribe(() => {
      this.closeOrderPanel();
      this.reloadTables();
    });
  }

  addToDraft(item: MenuItem): void {
    this.draftItems.update((items) => {
      const existing = items.find((i) => i.menuItemId === item.id);
      if (existing) {
        return items.map((i) =>
          i.menuItemId === item.id ? { ...i, quantity: i.quantity + 1 } : i,
        );
      }
      return [...items, { menuItemId: item.id, name: item.name, price: item.price, quantity: 1 }];
    });
  }

  removeFromDraft(menuItemId: number): void {
    this.draftItems.update((items) => items.filter((i) => i.menuItemId !== menuItemId));
  }

  increaseDraftQuantity(menuItemId: number): void {
    this.draftItems.update((items) =>
      items.map((i) => (i.menuItemId === menuItemId ? { ...i, quantity: i.quantity + 1 } : i)),
    );
  }

  /** Decreasing past 1 removes the line entirely rather than allowing a 0 (or negative) quantity. */
  decreaseDraftQuantity(menuItemId: number): void {
    this.draftItems.update((items) =>
      items
        .map((i) => (i.menuItemId === menuItemId ? { ...i, quantity: i.quantity - 1 } : i))
        .filter((i) => i.quantity > 0),
    );
  }

  cancelOrder(): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.orderApi.cancelOrder(order.id).subscribe(() => {
      this.closeOrderPanel();
      this.reloadTables();
    });
  }

  /** The one Confirm action for both cases: opening a brand-new order, or retrying a failed one. */
  confirm(): void {
    const table = this.selectedTable();
    const order = this.currentOrder();
    if (!table || !this.confirmEnabled()) {
      return;
    }
    const items = this.draftItems().map((item) => ({
      menuItemId: item.menuItemId,
      quantity: item.quantity,
    }));
    this.checkingOut.set(true);
    const request = order
      ? this.orderApi.checkout(order.id, { items })
      : this.orderApi.createOrder({ tableId: table.id, items });
    request.subscribe({
      next: (updated) => {
        this.currentOrder.set(updated);
        this.draftItems.set(this.toDraftItems(updated.items));
        this.pollUntilSettled(updated.id, 0);
      },
      error: () => this.checkingOut.set(false),
    });
  }

  pay(paymentMethod: string): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.checkingOut.set(true);
    this.orderApi.pay(order.id, { paymentMethod }).subscribe({
      next: (updated) => {
        this.currentOrder.set(updated);
        this.pollUntilSettled(updated.id, 0);
      },
      error: () => this.checkingOut.set(false),
    });
  }

  private pollUntilSettled(orderId: number, attempt: number): void {
    if (attempt >= POLL_MAX_ATTEMPTS) {
      this.checkingOut.set(false);
      return;
    }
    setTimeout(() => {
      this.orderApi.getOrder(orderId).subscribe((order) => {
        this.currentOrder.set(order);
        this.draftItems.set(this.toDraftItems(order.items));
        if (order.status === 'PENDING_CONFIRMATION' || order.status === 'PAYMENT_PENDING') {
          this.pollUntilSettled(orderId, attempt + 1);
        } else {
          this.checkingOut.set(false);
          this.reloadTables();
        }
      });
    }, POLL_INTERVAL_MS);
  }
}
