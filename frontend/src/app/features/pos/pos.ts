import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  Observable,
  Subject,
  catchError,
  exhaustMap,
  finalize,
  forkJoin,
  of,
  take,
  takeUntil,
  takeWhile,
  tap,
  timer,
} from 'rxjs';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { Category } from '../../core/models/category.model';
import { DiningTable } from '../../core/models/dining-table.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { DraftItem, Order, OrderItem } from '../../core/models/order.model';
import { OrderApiService } from '../../core/order/order-api.service';
import { LanguageSwitcher } from '../../shared/language-switcher/language-switcher';

const POLL_INTERVAL_MS = 1000;
const POLL_MAX_ATTEMPTS = 10;
/** Mirrors order-service's @Size(max = 50) on CreateOrderRequest/CheckoutRequest.items. */
const MAX_DRAFT_ITEMS = 50;

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
  private readonly destroyRef = inject(DestroyRef);

  readonly tables = signal<DiningTable[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly menuItems = signal<MenuItem[]>([]);
  readonly selectedTable = signal<DiningTable | null>(null);
  readonly currentOrder = signal<Order | null>(null);
  readonly draftItems = signal<DraftItem[]>([]);
  readonly checkingOut = signal(false);
  /** Which of confirm()/pay() is behind the current checkingOut() spinner - set alongside checkingOut(true), cleared alongside checkingOut(false) in submitAndPoll(). Drives which "processing..." message the panel shows. */
  readonly processingAction = signal<'confirm' | 'pay' | null>(null);
  readonly movingTable = signal(false);
  readonly actionError = signal<string | null>(null);
  /** True while fetching the selected table's current order - see releaseAbandonedDraftRequest(). */
  readonly loadingOrder = signal(false);
  readonly maxDraftItems = MAX_DRAFT_ITEMS;

  /**
   * Emits whenever the user moves on from whatever table/order they were looking at (switching
   * tables, closing the panel) - every request/poll tied to the *previous* selection is piped
   * through `takeUntil(this.selectionChanged$)` so a response that lands after the user has
   * already moved on never overwrites what's currently on screen. Without this, `selectTable()`'s
   * own occupy/getCurrentOrder calls and the confirm()/pay() poll loop can apply a stale table's
   * data (or even money-relevant state like a CONFIRMED order) on top of a completely different,
   * currently-selected table.
   */
  private readonly selectionChanged$ = new Subject<void>();

  constructor() {
    this.reloadTables();
    this.menuApi.listCategories().subscribe((categories) => this.categories.set(categories));
    this.menuApi
      .listMenuItems()
      .subscribe((items) => this.menuItems.set(items.filter((item) => item.available)));
    this.destroyRef.onDestroy(() => {
      this.selectionChanged$.next();
      this.selectionChanged$.complete();
    });
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

  /** Payment can only be taken once the saga's verify leg has reserved stock (order status CONFIRMED). */
  canPay(): boolean {
    return this.currentOrder()?.status === 'CONFIRMED';
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
        // Cancels a still-in-flight moveToTable() request for a destination the user has since
        // changed their mind about - otherwise whichever response lands last (not necessarily the
        // destination just picked) would silently overwrite the display and move the order there.
        this.selectionChanged$.next();
        this.moveToTable(table);
      }
      return;
    }
    if (this.selectedTable()?.id === table.id) {
      return;
    }
    // Must read releaseAbandonedDraftRequest() (which checks loadingOrder()) *before* cancelling
    // in-flight work below - a still-pending getCurrentOrderForTable for the old table clears
    // loadingOrder via finalize() the moment it's cancelled, so checking it after would always see
    // `false` and defeat the whole guard.
    const release$ = this.releaseAbandonedDraftRequest();
    // Cancel every request/poll still in flight for whatever was selected before - their results
    // are no longer relevant to what's about to be shown. Everything this switch itself is about
    // to kick off below subscribes to selectionChanged$ *after* this point, so this only cancels
    // the *previous* selection's work, never its own.
    this.selectionChanged$.next();
    this.selectedTable.set(table);
    this.currentOrder.set(null);
    this.draftItems.set([]);
    this.actionError.set(null);
    if (table.status === 'AVAILABLE') {
      // takeUntil is on occupy$ alone, not on the forkJoin below - release$ (freeing the *old*
      // table) must keep running to completion even if the user switches again before this
      // settles; only occupy$ (for this now-abandoned selection) should be cut short. If occupy$
      // gets cancelled, forkJoin below simply never emits (it needs every source to produce a
      // value) and reloadTables() is skipped - harmless, since whatever the user switched to next
      // triggers its own reload anyway.
      const occupy$ = this.orderApi.occupyTable(table.id).pipe(
        tap({
          error: () => {
            // Someone else already occupied it (or another failure) - back out of the selection
            // instead of leaving a draft cart open on a table we don't actually hold.
            this.selectedTable.set(null);
          },
        }),
        catchError(() => of(null)),
        takeUntil(this.selectionChanged$),
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
      this.loadingOrder.set(true);
      this.orderApi
        .getCurrentOrderForTable(table.id)
        .pipe(
          takeUntil(this.selectionChanged$),
          finalize(() => this.loadingOrder.set(false)),
        )
        .subscribe({
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
    // Same ordering requirement as selectTable(): read releaseAbandonedDraftRequest() (checks
    // loadingOrder()) before selectionChanged$ cancels the in-flight fetch that flag depends on.
    const release$ = this.releaseAbandonedDraftRequest();
    this.selectionChanged$.next();
    release$?.subscribe(() => this.reloadTables());
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
   *
   * Also refuses while `loadingOrder()` is true: `currentOrder()` being null can mean either
   * "nothing was ever confirmed" *or* "we don't know yet, the fetch is still in flight" - treating
   * the second case as the first would release a table that may well have a live order on it.
   * Skipping the release here leaves the table OCCUPIED until staff reopen and close it again
   * (the same manual recovery this method already relies on elsewhere) - safer than guessing.
   */
  private releaseAbandonedDraftRequest(): Observable<unknown> | null {
    const table = this.selectedTable();
    if (!table || this.currentOrder() || this.loadingOrder()) {
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
    this.orderApi
      .moveTable(order.id, { tableId: table.id })
      .pipe(takeUntil(this.selectionChanged$))
      .subscribe({
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

  private increaseQuantity(items: DraftItem[], menuItemId: number): DraftItem[] {
    return items.map((i) => (i.menuItemId === menuItemId ? { ...i, quantity: i.quantity + 1 } : i));
  }

  /** True once the draft cart already holds maxDraftItems distinct lines. */
  isCartFull(): boolean {
    return this.draftItems().length >= this.maxDraftItems;
  }

  /** A line already in the cart can always have its quantity bumped, even once the cart is full. */
  canAddToDraft(item: MenuItem): boolean {
    return !this.isCartFull() || this.draftItems().some((i) => i.menuItemId === item.id);
  }

  addToDraft(item: MenuItem): void {
    if (!this.canAddToDraft(item)) {
      return;
    }
    this.draftItems.update((items) => {
      if (items.some((i) => i.menuItemId === item.id)) {
        return this.increaseQuantity(items, item.id);
      }
      return [...items, { menuItemId: item.id, name: item.name, price: item.price, quantity: 1 }];
    });
  }

  removeFromDraft(menuItemId: number): void {
    this.draftItems.update((items) => items.filter((i) => i.menuItemId !== menuItemId));
  }

  increaseDraftQuantity(menuItemId: number): void {
    this.draftItems.update((items) => this.increaseQuantity(items, menuItemId));
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
    if (!table || !this.confirmEnabled() || !this.canEdit()) {
      return;
    }
    const items = this.draftItems().map((item) => ({
      menuItemId: item.menuItemId,
      quantity: item.quantity,
    }));
    this.checkingOut.set(true);
    this.processingAction.set('confirm');
    this.actionError.set(null);
    const request = order
      ? this.orderApi.checkout(order.id, { items })
      : this.orderApi.createOrder({ tableId: table.id, items });
    this.submitAndPoll(request, (updated) => {
      this.currentOrder.set(updated);
      this.draftItems.set(this.toDraftItems(updated.items));
      this.startPolling(updated.id);
    });
  }

  pay(paymentMethod: string): void {
    const order = this.currentOrder();
    if (!order || !this.canPay()) {
      return;
    }
    this.checkingOut.set(true);
    this.processingAction.set('pay');
    this.actionError.set(null);
    this.submitAndPoll(this.orderApi.pay(order.id, { paymentMethod }), (updated) => {
      this.currentOrder.set(updated);
      this.startPolling(updated.id);
    });
  }

  /**
   * Shared subscribe logic for confirm()/pay(): both submit a request, then only reset
   * `checkingOut` if the request never actually came back (an error, or `takeUntil` cancelling it
   * because the user moved on). `succeeded` distinguishes "completed because the request came
   * back" from "completed because takeUntil cancelled it" - complete() fires in both cases, but
   * checkingOut should only be reset for the latter (on success, it stays true while
   * startPolling() takes over, driven by onSuccess).
   */
  private submitAndPoll<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    let succeeded = false;
    request.pipe(takeUntil(this.selectionChanged$)).subscribe({
      next: (value) => {
        succeeded = true;
        onSuccess(value);
      },
      error: (error: unknown) => {
        this.checkingOut.set(false);
        this.processingAction.set(null);
        this.actionError.set(this.extractErrorMessage(error));
      },
      complete: () => {
        if (!succeeded) {
          this.checkingOut.set(false);
          this.processingAction.set(null);
        }
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

  /**
   * Polls an order every second, up to POLL_MAX_ATTEMPTS times, until it settles out of
   * PENDING_CONFIRMATION/PAYMENT_PENDING (the saga's async legs land via Kafka, not a direct
   * response to confirm()/pay()). `takeUntil(this.selectionChanged$)` stops the whole chain dead -
   * no further ticks, no more state updates - the moment the user moves on to a different
   * table/panel, so a poll response for an order nobody is looking at anymore can't land on top of
   * whatever's currently displayed.
   */
  private startPolling(orderId: number): void {
    let settled = false;
    timer(POLL_INTERVAL_MS, POLL_INTERVAL_MS)
      .pipe(
        take(POLL_MAX_ATTEMPTS),
        // Each tick waits for the previous getOrder() to finish before firing the next one; a
        // tick that arrives while one is still outstanding is dropped rather than restarting the
        // request - otherwise a real settled response landing close to a tick boundary could be
        // discarded, wasting an attempt for nothing.
        exhaustMap(() => this.orderApi.getOrder(orderId)),
        tap((order) => {
          this.currentOrder.set(order);
          this.draftItems.set(this.toDraftItems(order.items));
          settled = order.status !== 'PENDING_CONFIRMATION' && order.status !== 'PAYMENT_PENDING';
        }),
        // Inclusive: the settling response itself must still get through the tap above before
        // this stops the timer - only the *next* tick (or attempts beyond POLL_MAX_ATTEMPTS) is
        // actually skipped.
        takeWhile(() => !settled, true),
        takeUntil(this.selectionChanged$),
      )
      .subscribe({
        error: (error: unknown) => {
          this.checkingOut.set(false);
          this.processingAction.set(null);
          this.actionError.set(this.extractErrorMessage(error));
        },
        complete: () => {
          this.checkingOut.set(false);
          this.processingAction.set(null);
          // Not reached if takeUntil cut this short (settled stays false) or if
          // POLL_MAX_ATTEMPTS was exhausted without settling - only reload the table list
          // once an order actually leaves the saga's care (settles out of PENDING_CONFIRMATION).
          if (settled) {
            this.reloadTables();
          }
        },
      });
  }
}
