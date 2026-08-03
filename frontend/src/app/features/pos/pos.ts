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
import { Order } from '../../core/models/order.model';
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
    LanguageSwitcher
  ],
  templateUrl: './pos.html',
  styleUrl: './pos.scss'
})
export class Pos {
  private readonly orderApi = inject(OrderApiService);
  private readonly menuApi = inject(MenuApiService);

  readonly tables = signal<DiningTable[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly menuItems = signal<MenuItem[]>([]);
  readonly selectedTable = signal<DiningTable | null>(null);
  readonly currentOrder = signal<Order | null>(null);
  readonly checkingOut = signal(false);
  readonly movingTable = signal(false);

  constructor() {
    this.reloadTables();
    this.menuApi.listCategories().subscribe((categories) => this.categories.set(categories));
    this.menuApi.listMenuItems().subscribe((items) => this.menuItems.set(items.filter((item) => item.available)));
  }

  private reloadTables(): void {
    this.orderApi.listTables().subscribe((tables) => this.tables.set(tables));
  }

  itemsByCategory(categoryId: number): MenuItem[] {
    return this.menuItems().filter((item) => item.categoryId === categoryId);
  }

  selectTable(table: DiningTable): void {
    if (this.movingTable()) {
      if (table.status === 'AVAILABLE') {
        this.moveToTable(table);
      }
      return;
    }
    this.selectedTable.set(table);
    if (table.status === 'AVAILABLE') {
      this.orderApi.createOrder({ tableId: table.id }).subscribe((order) => {
        this.currentOrder.set(order);
        this.reloadTables();
      });
    } else {
      this.orderApi.getCurrentOrderForTable(table.id).subscribe({
        next: (order) => this.currentOrder.set(order),
        error: () => this.selectedTable.set(null)
      });
    }
  }

  closeOrderPanel(): void {
    this.selectedTable.set(null);
    this.currentOrder.set(null);
    this.movingTable.set(false);
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

  addItem(item: MenuItem): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.orderApi.addItem(order.id, { menuItemId: item.id, quantity: 1 }).subscribe((updated) => this.currentOrder.set(updated));
  }

  removeItem(orderItemId: number): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.orderApi.removeItem(order.id, orderItemId).subscribe((updated) => this.currentOrder.set(updated));
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

  verify(): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.checkingOut.set(true);
    this.orderApi.checkout(order.id).subscribe({
      next: (updated) => {
        this.currentOrder.set(updated);
        this.pollUntilSettled(updated.id, 0);
      },
      error: () => this.checkingOut.set(false)
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
      error: () => this.checkingOut.set(false)
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
