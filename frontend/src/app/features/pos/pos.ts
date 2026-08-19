import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { Observable, catchError, forkJoin, of, tap } from 'rxjs';
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
  private readonly transloco = inject(TranslocoService);

  readonly tables = signal<DiningTable[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly menuItems = signal<MenuItem[]>([]);
  readonly selectedTable = signal<DiningTable | null>(null);
  readonly currentOrder = signal<Order | null>(null);
  readonly draftItems = signal<DraftItem[]>([]);
  readonly checkingOut = signal(false);
  readonly movingTable = signal(false);
  readonly actionError = signal<string | null>(null);

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

  /**
   * Enabled once there's something to submit - whether that's a brand-new order, a retry of a
   * failed one, or the same items an OPEN order already holds (it was never actually verified,
   * so resubmitting as-is must stay possible, not just after an edit).
   */
  confirmEnabled(): boolean {
    return this.draftItems().length > 0;
  }

  grandTotal(): number {
    if (this.canEdit()) {
      return this.draftItems().reduce((sum, item) => sum + item.price * item.quantity, 0);
    }
    return this.currentOrder()?.grandTotal ?? 0;
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
    if (this.selectedTable()?.id === table.id) {
      return;
    }
    const release$ = this.releaseAbandonedDraftRequest();
    this.selectedTable.set(table);
    this.currentOrder.set(null);
    this.draftItems.set([]);
    this.actionError.set(null);
    if (table.status === 'AVAILABLE') {
      const occupy$ = this.orderApi.occupyTable(table.id).pipe(
        tap({
          error: () => {
            // Someone else already occupied it (or another failure) - back out of the selection
            // instead of leaving a draft cart open on a table we don't actually hold.
            this.selectedTable.set(null);
          },
        }),
        catchError(() => of(null)),
      );
      // Wait for both the old table's release (if any) and the new table's occupy to settle
      // before reloading, rather than reloading once per request - reloading as soon as just one
      // of them lands can hit the backend before the other has committed, showing a table's old
      // status instead of its new one.
      if (release$) {
        forkJoin([release$, occupy$]).subscribe(() => this.reloadTables());
      } else {
        occupy$.subscribe(() => this.reloadTables());
      }
    } else {
      release$?.subscribe(() => this.reloadTables());
      this.orderApi.getCurrentOrderForTable(table.id).subscribe({
        next: (order) => {
          this.currentOrder.set(order);
          this.draftItems.set(this.toDraftItems(order.items));
        },
        error: (error: HttpErrorResponse) => {
          // A 404 means the table is OCCUPIED but nothing was ever confirmed on it (staff picked
          // items and closed the panel, or reloaded mid-draft) - not an error, just an empty cart
          // to build. Anything else (network/server failure) is a real error - back out rather
          // than showing a false empty draft on a table we don't actually know the state of.
          if (error.status !== 404) {
            this.selectedTable.set(null);
          }
        },
      });
    }
  }

  closeOrderPanel(): void {
    this.releaseAbandonedDraftRequest()?.subscribe(() => this.reloadTables());
    this.selectedTable.set(null);
    this.currentOrder.set(null);
    this.draftItems.set([]);
    this.movingTable.set(false);
    this.actionError.set(null);
  }

  /**
   * The release request for the currently selected table if nothing was ever confirmed on it -
   * `null` if there's nothing to release. A draft cart is local-only, so leaving it (via the
   * close button, or by picking a different table before confirming) must not leave the table
   * OCCUPIED with nothing to show for it. Returns the request itself rather than firing it
   * eagerly, since callers that are also about to fire another request (e.g. switching tables
   * also occupies the new one) need to combine both before reloading the table list once.
   */
  private releaseAbandonedDraftRequest(): Observable<unknown> | null {
    const table = this.selectedTable();
    if (!table || this.currentOrder()) {
      return null;
    }
    return this.orderApi.releaseTable(table.id).pipe(catchError(() => of(null)));
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
    this.orderApi.moveTable(order.id, { tableId: table.id }).subscribe({
      next: (updated) => {
        this.currentOrder.set(updated);
        this.selectedTable.set(table);
        this.movingTable.set(false);
        this.actionError.set(null);
        this.reloadTables();
      },
      error: (error: unknown) => {
        this.movingTable.set(false);
        this.actionError.set(this.extractErrorMessage(error));
        this.reloadTables();
      },
    });
  }

  /** Staff marks the table empty independent of payment status (pay-first-then-dine is allowed here). */
  releaseTable(): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.orderApi.releaseTable(order.tableId).subscribe({
      next: () => {
        this.closeOrderPanel();
        this.reloadTables();
      },
      error: () => this.reloadTables(),
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
    this.orderApi.cancelOrder(order.id).subscribe({
      next: () => {
        this.closeOrderPanel();
        this.reloadTables();
      },
      error: () => this.reloadTables(),
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
    this.actionError.set(null);
    const request = order
      ? this.orderApi.checkout(order.id, { items })
      : this.orderApi.createOrder({ tableId: table.id, items });
    request.subscribe({
      next: (updated) => {
        this.currentOrder.set(updated);
        this.draftItems.set(this.toDraftItems(updated.items));
        this.pollUntilSettled(updated.id, 0);
      },
      error: (error: unknown) => {
        this.checkingOut.set(false);
        this.actionError.set(this.extractErrorMessage(error));
      },
    });
  }

  pay(paymentMethod: string): void {
    const order = this.currentOrder();
    if (!order) {
      return;
    }
    this.checkingOut.set(true);
    this.actionError.set(null);
    this.orderApi.pay(order.id, { paymentMethod }).subscribe({
      next: (updated) => {
        this.currentOrder.set(updated);
        this.pollUntilSettled(updated.id, 0);
      },
      error: (error: unknown) => {
        this.checkingOut.set(false);
        this.actionError.set(this.extractErrorMessage(error));
      },
    });
  }

  /**
   * confirm()/pay() can fail for reasons that never touch the order (e.g. the table got taken by
   * someone else, or a picked item was 86'd between loading the menu and hitting Confirm) - there's
   * no `failureReason` on the order to show for those, since it was never persisted or never
   * changed. Backend errors carry a human-readable `message` (see ApiError); anything else (a
   * network failure with no response body) falls back to a generic message.
   */
  private extractErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse && typeof error.error?.message === 'string') {
      return error.error.message;
    }
    return this.transloco.translate('pos.actionFailedGeneric');
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
