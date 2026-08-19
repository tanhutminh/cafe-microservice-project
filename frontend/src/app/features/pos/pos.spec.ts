import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { Category } from '../../core/models/category.model';
import { DiningTable } from '../../core/models/dining-table.model';
import { MenuItem } from '../../core/models/menu-item.model';
import { Order } from '../../core/models/order.model';
import { MenuApiService } from '../../core/menu/menu-api.service';
import { OrderApiService } from '../../core/order/order-api.service';
import { provideTranslocoTesting } from '../../shared/testing/transloco-testing';
import { Pos } from './pos';

describe('Pos', () => {
  const availableTable: DiningTable = {
    id: 3,
    tableNumber: 'T3',
    capacity: 4,
    status: 'AVAILABLE',
    active: true,
  };
  const occupiedTable: DiningTable = { ...availableTable, status: 'OCCUPIED' };
  const anotherAvailableTable: DiningTable = { ...availableTable, id: 5, tableNumber: 'T5' };
  const category: Category = { id: 1, name: 'Coffee', displayOrder: 1, active: true };
  const menuItem: MenuItem = {
    id: 7,
    categoryId: 1,
    categoryName: 'Coffee',
    name: 'Latte',
    description: null,
    price: 45000,
    imageUrl: null,
    available: true,
    active: true,
  };
  const openOrder: Order = {
    id: 101,
    tableId: 3,
    tableNumber: 'T3',
    status: 'OPEN',
    paymentMethod: null,
    failureReason: null,
    items: [{ id: 1, menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 }],
    grandTotal: 90000,
    createdAt: '2026-08-18T00:00:00Z',
    closedAt: null,
  };

  let orderApi: {
    listTables: ReturnType<typeof vi.fn>;
    occupyTable: ReturnType<typeof vi.fn>;
    getOrder: ReturnType<typeof vi.fn>;
    getCurrentOrderForTable: ReturnType<typeof vi.fn>;
    createOrder: ReturnType<typeof vi.fn>;
    cancelOrder: ReturnType<typeof vi.fn>;
    checkout: ReturnType<typeof vi.fn>;
    pay: ReturnType<typeof vi.fn>;
    moveTable: ReturnType<typeof vi.fn>;
    releaseTable: ReturnType<typeof vi.fn>;
  };
  let menuApi: {
    listCategories: ReturnType<typeof vi.fn>;
    listMenuItems: ReturnType<typeof vi.fn>;
  };

  function createComponent() {
    return TestBed.createComponent(Pos).componentInstance;
  }

  beforeEach(() => {
    orderApi = {
      listTables: vi.fn().mockReturnValue(of([availableTable])),
      occupyTable: vi.fn().mockReturnValue(of(occupiedTable)),
      getOrder: vi.fn().mockReturnValue(of(openOrder)),
      getCurrentOrderForTable: vi.fn().mockReturnValue(of(openOrder)),
      createOrder: vi.fn().mockReturnValue(of(openOrder)),
      cancelOrder: vi.fn().mockReturnValue(of(undefined)),
      checkout: vi.fn().mockReturnValue(of(openOrder)),
      pay: vi.fn().mockReturnValue(of(openOrder)),
      moveTable: vi.fn().mockReturnValue(of(openOrder)),
      releaseTable: vi.fn().mockReturnValue(of(occupiedTable)),
    };
    menuApi = {
      listCategories: vi.fn().mockReturnValue(of([category])),
      listMenuItems: vi.fn().mockReturnValue(of([menuItem])),
    };

    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [
        provideRouter([]),
        { provide: OrderApiService, useValue: orderApi },
        { provide: MenuApiService, useValue: menuApi },
      ],
    });
  });

  describe('draft mutations', () => {
    it('never call the API', () => {
      const component = createComponent();

      component.addToDraft(menuItem);
      component.increaseDraftQuantity(menuItem.id);
      component.decreaseDraftQuantity(menuItem.id);
      component.addToDraft(menuItem);
      component.removeFromDraft(menuItem.id);

      expect(orderApi.createOrder).not.toHaveBeenCalled();
      expect(orderApi.checkout).not.toHaveBeenCalled();
    });

    it('accumulate quantity when the same item is added twice', () => {
      const component = createComponent();

      component.addToDraft(menuItem);
      component.addToDraft(menuItem);

      expect(component.draftItems()).toEqual([
        { menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 },
      ]);
    });

    it('removes the line once quantity is decreased past 1', () => {
      const component = createComponent();
      component.addToDraft(menuItem);

      component.decreaseDraftQuantity(menuItem.id);

      expect(component.draftItems()).toEqual([]);
    });

    it('increases quantity without adding a new line', () => {
      const component = createComponent();
      component.addToDraft(menuItem);

      component.increaseDraftQuantity(menuItem.id);

      expect(component.draftItems()).toEqual([
        { menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 },
      ]);
    });

    it('decreases quantity without removing the line when more than 1 remains', () => {
      const component = createComponent();
      component.addToDraft(menuItem);
      component.increaseDraftQuantity(menuItem.id);
      component.increaseDraftQuantity(menuItem.id);

      component.decreaseDraftQuantity(menuItem.id);

      expect(component.draftItems()).toEqual([
        { menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 },
      ]);
    });
  });

  describe('canEdit()', () => {
    it('is true when there is no current order', () => {
      const component = createComponent();

      expect(component.canEdit()).toBe(true);
    });

    it('is true for an OPEN order', () => {
      const component = createComponent();
      component.currentOrder.set(openOrder);

      expect(component.canEdit()).toBe(true);
    });

    it('is false once the order has moved past OPEN', () => {
      const component = createComponent();
      component.currentOrder.set({ ...openOrder, status: 'CONFIRMED' });

      expect(component.canEdit()).toBe(false);
    });
  });

  describe('grandTotal()', () => {
    it('sums the draft when there is no order yet', () => {
      const component = createComponent();

      component.addToDraft(menuItem);
      component.increaseDraftQuantity(menuItem.id);

      expect(component.grandTotal()).toBe(90000);
    });

    it("sums the draft while editing an OPEN order, ignoring the order's own grandTotal", () => {
      const component = createComponent();
      component.currentOrder.set(openOrder);
      component.draftItems.set([{ menuItemId: 7, name: 'Latte', price: 45000, quantity: 1 }]);

      expect(component.grandTotal()).toBe(45000);
    });

    it("uses the order's grandTotal once it can no longer be edited", () => {
      const component = createComponent();
      component.currentOrder.set({ ...openOrder, status: 'CONFIRMED', grandTotal: 90000 });

      expect(component.grandTotal()).toBe(90000);
    });
  });

  describe('selectTable()', () => {
    it('occupies an AVAILABLE table without creating an order yet', () => {
      const component = createComponent();

      component.selectTable(availableTable);

      expect(orderApi.occupyTable).toHaveBeenCalledWith(3);
      expect(orderApi.createOrder).not.toHaveBeenCalled();
      expect(component.currentOrder()).toBeNull();
    });

    it('backs out of the selection when occupying an AVAILABLE table fails', () => {
      orderApi.occupyTable.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409 })),
      );
      const component = createComponent();

      component.selectTable(availableTable);

      expect(component.selectedTable()).toBeNull();
    });

    it('loads the current order for an OCCUPIED table', () => {
      const component = createComponent();

      component.selectTable(occupiedTable);

      expect(orderApi.getCurrentOrderForTable).toHaveBeenCalledWith(3);
      expect(component.currentOrder()).toEqual(openOrder);
      expect(component.draftItems()).toEqual([
        { menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 },
      ]);
    });

    it('shows an empty draft, not an error, when an OCCUPIED table has no order yet (404)', () => {
      orderApi.getCurrentOrderForTable.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 404 })),
      );
      const component = createComponent();

      component.selectTable(occupiedTable);

      expect(component.selectedTable()).toEqual(occupiedTable);
      expect(component.currentOrder()).toBeNull();
      expect(component.canEdit()).toBe(true);
    });

    it('backs out of the selection on a non-404 error instead of showing a false empty draft', () => {
      orderApi.getCurrentOrderForTable.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      const component = createComponent();

      component.selectTable(occupiedTable);

      expect(component.selectedTable()).toBeNull();
    });

    it('releases the previous table if nothing was ever confirmed on it, when switching to a different table', () => {
      const component = createComponent();
      component.selectTable(availableTable);
      orderApi.releaseTable.mockClear();

      component.selectTable(anotherAvailableTable);

      expect(orderApi.releaseTable).toHaveBeenCalledWith(3);
      expect(orderApi.occupyTable).toHaveBeenCalledWith(5);
      expect(component.selectedTable()).toEqual(anotherAvailableTable);
    });

    it('reloads the table list exactly once, only after both the release and the occupy settle, regardless of which settles first', () => {
      const component = createComponent();
      component.selectTable(availableTable);
      orderApi.listTables.mockClear();
      // Fresh Subjects, assigned only now - the first selectTable() above must keep using the
      // default synchronous mocks, or it would pick up these Subjects too and leave a stray
      // subscription that fires reloadTables() a second time when they later emit.
      const release$ = new Subject<DiningTable>();
      const occupy$ = new Subject<DiningTable>();
      orderApi.releaseTable.mockReturnValue(release$);
      orderApi.occupyTable.mockReturnValue(occupy$);

      component.selectTable(anotherAvailableTable);
      expect(orderApi.listTables).not.toHaveBeenCalled();

      release$.next(availableTable);
      release$.complete();
      expect(orderApi.listTables).not.toHaveBeenCalled();

      occupy$.next(anotherAvailableTable);
      occupy$.complete();
      expect(orderApi.listTables).toHaveBeenCalledTimes(1);
    });

    it('reloads the table list exactly once even when occupy settles before the release of the abandoned table', () => {
      const component = createComponent();
      component.selectTable(availableTable);
      orderApi.listTables.mockClear();
      const release$ = new Subject<DiningTable>();
      const occupy$ = new Subject<DiningTable>();
      orderApi.releaseTable.mockReturnValue(release$);
      orderApi.occupyTable.mockReturnValue(occupy$);

      component.selectTable(anotherAvailableTable);

      occupy$.next(anotherAvailableTable);
      occupy$.complete();
      expect(orderApi.listTables).not.toHaveBeenCalled();

      release$.next(availableTable);
      release$.complete();
      expect(orderApi.listTables).toHaveBeenCalledTimes(1);
    });

    it('does not release the previous table when it already has a real order, and still switches to the new one', () => {
      const component = createComponent();
      component.selectTable(occupiedTable);
      orderApi.releaseTable.mockClear();

      component.selectTable(anotherAvailableTable);

      expect(orderApi.releaseTable).not.toHaveBeenCalled();
      expect(orderApi.occupyTable).toHaveBeenCalledWith(5);
      expect(component.selectedTable()).toEqual(anotherAvailableTable);
    });

    it('releases the previous table when switching away from one reached via the 404 (no order yet) path', () => {
      orderApi.getCurrentOrderForTable.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 404 })),
      );
      const component = createComponent();
      component.selectTable(occupiedTable);
      orderApi.releaseTable.mockClear();

      component.selectTable(anotherAvailableTable);

      expect(orderApi.releaseTable).toHaveBeenCalledWith(3);
      expect(orderApi.occupyTable).toHaveBeenCalledWith(5);
      expect(component.selectedTable()).toEqual(anotherAvailableTable);
    });

    it('does nothing when re-selecting the already-selected AVAILABLE table, preserving the draft', () => {
      const component = createComponent();
      component.selectTable(availableTable);
      component.addToDraft(menuItem);
      orderApi.occupyTable.mockClear();
      orderApi.releaseTable.mockClear();

      component.selectTable(availableTable);

      expect(orderApi.occupyTable).not.toHaveBeenCalled();
      expect(orderApi.releaseTable).not.toHaveBeenCalled();
      expect(component.draftItems()).toEqual([
        { menuItemId: 7, name: 'Latte', price: 45000, quantity: 1 },
      ]);
    });

    it('does nothing when re-selecting the already-selected OCCUPIED table, preserving the loaded order', () => {
      const component = createComponent();
      component.selectTable(occupiedTable);
      orderApi.getCurrentOrderForTable.mockClear();
      orderApi.occupyTable.mockClear();
      orderApi.releaseTable.mockClear();

      component.selectTable(occupiedTable);

      expect(orderApi.getCurrentOrderForTable).not.toHaveBeenCalled();
      expect(orderApi.occupyTable).not.toHaveBeenCalled();
      expect(orderApi.releaseTable).not.toHaveBeenCalled();
      expect(component.currentOrder()).toEqual(openOrder);
      expect(component.draftItems()).toEqual([
        { menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 },
      ]);
    });
  });

  describe('closeOrderPanel()', () => {
    it('releases the table when nothing was ever confirmed on it', () => {
      const component = createComponent();
      component.selectedTable.set(availableTable);

      component.closeOrderPanel();

      expect(orderApi.releaseTable).toHaveBeenCalledWith(3);
    });

    it('clears a leftover action error from a previous confirm()/pay() failure', () => {
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);
      component.actionError.set('Menu item is not available: Latte');

      component.closeOrderPanel();

      expect(component.actionError()).toBeNull();
    });

    it('still reloads tables when releasing fails, so the list reflects the real state', () => {
      orderApi.releaseTable.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      const component = createComponent();
      component.selectedTable.set(availableTable);
      orderApi.listTables.mockClear();

      component.closeOrderPanel();

      expect(orderApi.listTables).toHaveBeenCalled();
    });

    it('does not release the table when an order already exists', () => {
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);

      component.closeOrderPanel();

      expect(orderApi.releaseTable).not.toHaveBeenCalled();
    });
  });

  describe('confirmEnabled()', () => {
    it('is false with an empty draft', () => {
      const component = createComponent();

      expect(component.confirmEnabled()).toBe(false);
    });

    it('is true for a fresh draft with items and no order yet', () => {
      const component = createComponent();

      component.addToDraft(menuItem);

      expect(component.confirmEnabled()).toBe(true);
    });

    it('stays true even when the draft exactly matches an OPEN order - it was never actually verified, so resubmitting as-is must still be possible', () => {
      const component = createComponent();
      component.currentOrder.set(openOrder);

      component.draftItems.set([{ menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 }]);

      expect(component.confirmEnabled()).toBe(true);
    });

    it('is true once the draft differs from the current order too', () => {
      const component = createComponent();
      component.currentOrder.set(openOrder);

      component.draftItems.set([{ menuItemId: 7, name: 'Latte', price: 45000, quantity: 3 }]);

      expect(component.confirmEnabled()).toBe(true);
    });
  });

  describe('confirm()', () => {
    it('calls createOrder when there is no current order yet', () => {
      const component = createComponent();
      component.selectedTable.set(availableTable);
      component.addToDraft(menuItem);

      component.confirm();

      expect(orderApi.createOrder).toHaveBeenCalledWith({
        tableId: 3,
        items: [{ menuItemId: 7, quantity: 1 }],
      });
      expect(orderApi.checkout).not.toHaveBeenCalled();
    });

    it('calls checkout when retrying an existing OPEN order', () => {
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);
      component.draftItems.set([{ menuItemId: 7, name: 'Latte', price: 45000, quantity: 3 }]);

      component.confirm();

      expect(orderApi.checkout).toHaveBeenCalledWith(101, {
        items: [{ menuItemId: 7, quantity: 3 }],
      });
      expect(orderApi.createOrder).not.toHaveBeenCalled();
    });

    it('still calls checkout when the draft exactly matches an unverified OPEN order', () => {
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);
      component.draftItems.set([{ menuItemId: 7, name: 'Latte', price: 45000, quantity: 2 }]);

      component.confirm();

      expect(orderApi.checkout).toHaveBeenCalledWith(101, {
        items: [{ menuItemId: 7, quantity: 2 }],
      });
    });

    it('stops the spinner and shows a generic message when the request errors with no server message', () => {
      orderApi.createOrder.mockReturnValue(throwError(() => new Error('fail')));
      const component = createComponent();
      component.selectedTable.set(availableTable);
      component.addToDraft(menuItem);

      component.confirm();

      expect(component.checkingOut()).toBe(false);
      expect(component.actionError()).toBeTruthy();
    });

    it('surfaces the backend error message when the order was never touched (e.g. table taken, item unavailable)', () => {
      orderApi.createOrder.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'Menu item is not available: Latte' },
            }),
        ),
      );
      const component = createComponent();
      component.selectedTable.set(availableTable);
      component.addToDraft(menuItem);

      component.confirm();

      expect(component.actionError()).toBe('Menu item is not available: Latte');
    });

    it('clears a previous error once a retry is attempted', () => {
      orderApi.createOrder.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 500, error: { message: 'boom' } })),
      );
      const component = createComponent();
      component.selectedTable.set(availableTable);
      component.addToDraft(menuItem);
      component.confirm();
      expect(component.actionError()).toBe('boom');

      component.confirm();

      expect(component.actionError()).toBeNull();
    });
  });

  describe('startMoveTable() / cancelMoveTable()', () => {
    it('toggles movingTable on and off', () => {
      const component = createComponent();

      component.startMoveTable();
      expect(component.movingTable()).toBe(true);

      component.cancelMoveTable();
      expect(component.movingTable()).toBe(false);
    });
  });

  describe('selectTable() while moving an order', () => {
    it('moves the current order to an AVAILABLE table and exits move mode', () => {
      const movedOrder: Order = { ...openOrder, tableId: 5, tableNumber: 'T5' };
      orderApi.moveTable.mockReturnValue(of(movedOrder));
      const availableTarget: DiningTable = { ...availableTable, id: 5, tableNumber: 'T5' };
      const component = createComponent();
      component.currentOrder.set(openOrder);
      component.movingTable.set(true);

      component.selectTable(availableTarget);

      expect(orderApi.moveTable).toHaveBeenCalledWith(101, { tableId: 5 });
      expect(component.currentOrder()).toEqual(movedOrder);
      expect(component.movingTable()).toBe(false);
    });

    it('clears a leftover action error from a previous confirm()/pay() failure on a successful move', () => {
      const movedOrder: Order = { ...openOrder, tableId: 5, tableNumber: 'T5' };
      orderApi.moveTable.mockReturnValue(of(movedOrder));
      const availableTarget: DiningTable = { ...availableTable, id: 5, tableNumber: 'T5' };
      const component = createComponent();
      component.currentOrder.set(openOrder);
      component.movingTable.set(true);
      component.actionError.set('Order is already paid: 101');

      component.selectTable(availableTarget);

      expect(component.actionError()).toBeNull();
    });

    it('does nothing when the move target is OCCUPIED', () => {
      const component = createComponent();
      component.currentOrder.set(openOrder);
      component.movingTable.set(true);

      component.selectTable(occupiedTable);

      expect(orderApi.moveTable).not.toHaveBeenCalled();
    });

    it('exits move mode without calling the API when there is no current order', () => {
      const component = createComponent();
      component.movingTable.set(true);

      component.selectTable(availableTable);

      expect(orderApi.moveTable).not.toHaveBeenCalled();
      expect(component.movingTable()).toBe(false);
    });

    it('exits move mode and reloads tables when the move fails', () => {
      orderApi.moveTable.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
      const availableTarget: DiningTable = { ...availableTable, id: 5, tableNumber: 'T5' };
      const component = createComponent();
      component.currentOrder.set(openOrder);
      component.movingTable.set(true);
      orderApi.listTables.mockClear();

      component.selectTable(availableTarget);

      expect(component.movingTable()).toBe(false);
      expect(orderApi.listTables).toHaveBeenCalled();
    });

    it('surfaces the real reason when the move itself fails, replacing any leftover action error', () => {
      orderApi.moveTable.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'Cannot move table while a saga step is in progress: 101' },
            }),
        ),
      );
      const availableTarget: DiningTable = { ...availableTable, id: 5, tableNumber: 'T5' };
      const component = createComponent();
      component.currentOrder.set(openOrder);
      component.movingTable.set(true);
      component.actionError.set('Order is already paid: 101');

      component.selectTable(availableTarget);

      expect(component.actionError()).toBe(
        'Cannot move table while a saga step is in progress: 101',
      );
    });
  });

  describe('cancelOrder()', () => {
    it('cancels, closes the panel, and reloads tables', () => {
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);

      component.cancelOrder();

      expect(orderApi.cancelOrder).toHaveBeenCalledWith(101);
      expect(component.currentOrder()).toBeNull();
      expect(component.selectedTable()).toBeNull();
    });

    it('does nothing when there is no current order', () => {
      const component = createComponent();

      component.cancelOrder();

      expect(orderApi.cancelOrder).not.toHaveBeenCalled();
    });

    it('still reloads tables when cancelling fails', () => {
      orderApi.cancelOrder.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);
      orderApi.listTables.mockClear();

      component.cancelOrder();

      expect(orderApi.listTables).toHaveBeenCalled();
    });
  });

  describe('releaseTable()', () => {
    it('releases the table by tableId exactly once and closes the panel', () => {
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);

      component.releaseTable();

      expect(orderApi.releaseTable).toHaveBeenCalledWith(openOrder.tableId);
      expect(orderApi.releaseTable).toHaveBeenCalledTimes(1);
      expect(component.currentOrder()).toBeNull();
    });

    it('does nothing when there is no current order', () => {
      const component = createComponent();

      component.releaseTable();

      expect(orderApi.releaseTable).not.toHaveBeenCalled();
    });

    it('still reloads tables when releasing fails', () => {
      orderApi.releaseTable.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      const component = createComponent();
      component.selectedTable.set(occupiedTable);
      component.currentOrder.set(openOrder);
      orderApi.listTables.mockClear();

      component.releaseTable();

      expect(orderApi.listTables).toHaveBeenCalled();
    });
  });

  describe('pay()', () => {
    it('starts payment and shows the spinner', () => {
      const component = createComponent();
      component.currentOrder.set({ ...openOrder, status: 'CONFIRMED' });

      component.pay('CASH');

      expect(orderApi.pay).toHaveBeenCalledWith(101, { paymentMethod: 'CASH' });
      expect(component.checkingOut()).toBe(true);
    });

    it('does nothing when there is no current order', () => {
      const component = createComponent();

      component.pay('CASH');

      expect(orderApi.pay).not.toHaveBeenCalled();
    });

    it('stops the spinner and shows a generic message when the request errors with no server message', () => {
      orderApi.pay.mockReturnValue(throwError(() => new Error('fail')));
      const component = createComponent();
      component.currentOrder.set({ ...openOrder, status: 'CONFIRMED' });

      component.pay('CASH');

      expect(component.checkingOut()).toBe(false);
      expect(component.actionError()).toBeTruthy();
    });

    it('surfaces the backend error message when payment fails', () => {
      orderApi.pay.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'Order must be verified before payment: 101' },
            }),
        ),
      );
      const component = createComponent();
      component.currentOrder.set({ ...openOrder, status: 'CONFIRMED' });

      component.pay('CASH');

      expect(component.actionError()).toBe('Order must be verified before payment: 101');
    });
  });

  describe('pollUntilSettled (driven via confirm())', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('keeps polling while PENDING_CONFIRMATION, then stops once settled', async () => {
      const pending: Order = { ...openOrder, status: 'PENDING_CONFIRMATION' };
      const confirmed: Order = { ...openOrder, status: 'CONFIRMED' };
      orderApi.createOrder.mockReturnValue(of(pending));
      orderApi.getOrder.mockReturnValueOnce(of(pending)).mockReturnValueOnce(of(confirmed));
      const component = createComponent();
      component.selectedTable.set(availableTable);
      component.addToDraft(menuItem);

      component.confirm();
      expect(component.checkingOut()).toBe(true);

      await vi.advanceTimersByTimeAsync(1000);
      expect(orderApi.getOrder).toHaveBeenCalledTimes(1);
      expect(component.checkingOut()).toBe(true);

      await vi.advanceTimersByTimeAsync(1000);
      expect(orderApi.getOrder).toHaveBeenCalledTimes(2);
      expect(component.checkingOut()).toBe(false);
      expect(component.currentOrder()?.status).toBe('CONFIRMED');
    });

    it('gives up and stops the spinner after the max poll attempts', async () => {
      const pending: Order = { ...openOrder, status: 'PENDING_CONFIRMATION' };
      orderApi.createOrder.mockReturnValue(of(pending));
      orderApi.getOrder.mockReturnValue(of(pending));
      const component = createComponent();
      component.selectedTable.set(availableTable);
      component.addToDraft(menuItem);

      component.confirm();
      await vi.advanceTimersByTimeAsync(1000 * 11);

      expect(orderApi.getOrder).toHaveBeenCalledTimes(10);
      expect(component.checkingOut()).toBe(false);
    });
  });
});
