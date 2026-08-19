package com.cafe.orderservice.order;

import com.cafe.common.event.OrderLineItem;
import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.client.MenuServiceClient;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.DiningTableService;
import com.cafe.orderservice.table.TableStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  /**
   * Statuses that mean a customer is actively being served on the table right now — blocks creating
   * a second order there. Wider than {@link DiningTableService}'s own ACTIVE_ORDER_STATUSES (which
   * only guards releasing/moving a table): PAID is deliberately excluded here too, same as there,
   * since a paid-but-not-yet-released table is free to start a new order on (pay-first-then-dine).
   */
  private static final List<OrderStatus> IN_PROGRESS_STATUSES =
      List.of(
          OrderStatus.OPEN,
          OrderStatus.PENDING_CONFIRMATION,
          OrderStatus.CONFIRMED,
          OrderStatus.PAYMENT_PENDING);

  private final OrderRepository orderRepository;
  private final DiningTableService diningTableService;
  private final MenuServiceClient menuServiceClient;

  public OrderService(
      OrderRepository orderRepository,
      DiningTableService diningTableService,
      MenuServiceClient menuServiceClient) {
    this.orderRepository = orderRepository;
    this.diningTableService = diningTableService;
    this.menuServiceClient = menuServiceClient;
  }

  public Order getOrder(Long id) {
    return orderRepository
        .findByIdWithDetails(id)
        .orElseThrow(() -> ResourceNotFoundException.of("Order", id));
  }

  /**
   * The order currently holding a table (open, mid-checkout, or paid-but-not-yet-released) — used
   * by the POS screen when staff click an OCCUPIED table.
   */
  public Order getCurrentOrderForTable(Long tableId) {
    return orderRepository
        .findCurrentByTableId(tableId)
        .orElseThrow(() -> ResourceNotFoundException.of("Current order for table", tableId));
  }

  /**
   * Requires the table to already be OCCUPIED (see {@link DiningTableService#occupy}) — this method
   * never itself transitions table status, only verifies it. Also throws if another order is
   * already in progress on the table. Same caveat as {@link DiningTableService#occupy}: this
   * in-progress check-then-insert has no row version/lock guarding it yet, so it narrows but
   * doesn't fully close the window for two near-simultaneous requests on the same table.
   */
  @Transactional
  public Order createOrderWithItems(Long tableId, List<OrderLineItem> items) {
    if (orderRepository.existsByTable_IdAndStatusIn(tableId, IN_PROGRESS_STATUSES)) {
      throw new BusinessRuleException("Table already has an order in progress: " + tableId);
    }
    DiningTable table = diningTableService.findById(tableId);
    if (table.getStatus() != TableStatus.OCCUPIED) {
      throw new BusinessRuleException("Table is not occupied: " + tableId);
    }
    Order order = Order.builder().table(table).status(OrderStatus.OPEN).build();
    dedupeLines(items).forEach(line -> addResolvedItem(order, line));
    return orderRepository.save(order);
  }

  /**
   * Replaces the order's entire item list with {@code newItems} — used to submit a locally-built
   * draft cart when retrying a failed verify attempt (order status OPEN). Old lines not present in
   * {@code newItems} are deleted (relies on {@code orphanRemoval} on {@link Order#getItems()}), not
   * just superseded.
   */
  @Transactional
  public Order replaceItems(Long orderId, List<OrderLineItem> newItems) {
    Order order = getOrder(orderId);
    requireOpen(order);
    order.getItems().clear();
    dedupeLines(newItems).forEach(line -> addResolvedItem(order, line));
    return orderRepository.save(order);
  }

  /**
   * Keeps one line per menuItemId - the last occurrence wins, not a sum. The POS draft cart always
   * merges client-side before submitting, but the server shouldn't rely on that: without this, a
   * duplicate menuItemId across two lines would create two separate OrderItem rows instead of one.
   */
  private List<OrderLineItem> dedupeLines(List<OrderLineItem> lines) {
    Map<Long, Integer> quantityByMenuItemId = new LinkedHashMap<>();
    lines.forEach(line -> quantityByMenuItemId.put(line.menuItemId(), line.quantity()));
    return quantityByMenuItemId.entrySet().stream()
        .map(entry -> new OrderLineItem(entry.getKey(), entry.getValue()))
        .toList();
  }

  private void addResolvedItem(Order order, OrderLineItem line) {
    MenuItemDetails details = menuServiceClient.findMenuItem(line.menuItemId());
    if (!details.available()) {
      throw new BusinessRuleException("Menu item is not available: " + details.name());
    }
    order.addItem(
        OrderItem.builder()
            .menuItemId(details.id())
            .nameSnapshot(details.name())
            .priceSnapshot(details.price())
            .quantity(line.quantity())
            .build());
  }

  @Transactional
  public Order cancel(Long orderId) {
    Order order = getOrder(orderId);
    if (order.getStatus() == OrderStatus.PAID) {
      throw new BusinessRuleException("Cannot cancel a paid order: " + orderId);
    }
    if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION
        || order.getStatus() == OrderStatus.PAYMENT_PENDING) {
      throw new BusinessRuleException("Cannot cancel while a saga step is in progress: " + orderId);
    }
    Long tableId = order.getTable().getId();
    order.setStatus(OrderStatus.CANCELLED);
    order.setClosedAt(Instant.now());
    orderRepository.save(order);
    diningTableService.release(tableId);
    return order;
  }

  /**
   * Moves an order to a different table (e.g. the party relocates but keeps the same tab). Not
   * allowed while either saga leg is in flight (PENDING_CONFIRMATION / PAYMENT_PENDING) since the
   * saga tracks the order, not the table, and a mid-flight move could race with the reply.
   */
  @Transactional
  public Order moveTable(Long orderId, Long newTableId) {
    Order order = getOrder(orderId);
    if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION
        || order.getStatus() == OrderStatus.PAYMENT_PENDING) {
      throw new BusinessRuleException(
          "Cannot move table while a saga step is in progress: " + orderId);
    }
    if (order.getStatus() == OrderStatus.CANCELLED) {
      throw new BusinessRuleException("Cannot move a cancelled order: " + orderId);
    }
    Long oldTableId = order.getTable().getId();
    if (oldTableId.equals(newTableId)) {
      return order;
    }
    DiningTable newTable = diningTableService.findById(newTableId);
    diningTableService.occupy(newTableId);
    order.setTable(newTable);
    orderRepository.save(order);
    diningTableService.release(oldTableId);
    return order;
  }

  /**
   * Verify leg, local half — see OrderSaga for the saga state creation that joins this transaction.
   */
  @Transactional
  public Order checkout(Long orderId) {
    Order order = getOrder(orderId);
    requireOpen(order);
    if (order.getItems().isEmpty()) {
      throw new BusinessRuleException("Cannot checkout an empty order: " + orderId);
    }
    order.setStatus(OrderStatus.PENDING_CONFIRMATION);
    order.setFailureReason(null);
    return orderRepository.save(order);
  }

  /**
   * Verify success path — called by OrderSaga once inventory confirms the stock hold. Stock is
   * reserved but no payment has been taken yet; the cashier still has to call startPayment
   * separately via the /pay endpoint.
   */
  @Transactional
  public Order markConfirmed(Long orderId) {
    Order order = getOrder(orderId);
    order.setStatus(OrderStatus.CONFIRMED);
    return orderRepository.save(order);
  }

  /** Payment leg, local half — only valid once stock has been verified/reserved (CONFIRMED). */
  @Transactional
  public Order startPayment(Long orderId, String paymentMethod) {
    Order order = getOrder(orderId);
    if (order.getStatus() != OrderStatus.CONFIRMED) {
      throw new BusinessRuleException(paymentBlockedMessage(order));
    }
    order.setStatus(OrderStatus.PAYMENT_PENDING);
    order.setPaymentMethod(paymentMethod);
    order.setFailureReason(null);
    return orderRepository.save(order);
  }

  /**
   * A generic "must be verified before payment" is only accurate for OPEN/PENDING_CONFIRMATION -
   * for the other non-CONFIRMED statuses it's actively misleading (e.g. telling staff to verify an
   * order that's already PAID, when the real problem is someone else already paid it - a real race
   * between two POS terminals hitting Pay around the same time).
   */
  private String paymentBlockedMessage(Order order) {
    String reason =
        switch (order.getStatus()) {
          case PAID -> "Order is already paid";
          case PAYMENT_PENDING -> "Payment is already in progress";
          case CANCELLED -> "Cannot pay a cancelled order";
          default -> "Order must be verified before payment";
        };
    return reason + ": " + order.getId();
  }

  /**
   * Payment success path — called by OrderSaga once inventory confirms the stock commit. Does not
   * release the table: paying doesn't mean the party has left (pay-first-then-dine is a valid flow
   * here). Staff frees the table explicitly via DiningTableService.release() once it's actually
   * empty.
   */
  @Transactional
  public Order markPaid(Long orderId) {
    Order order = getOrder(orderId);
    order.setStatus(OrderStatus.PAID);
    order.setClosedAt(Instant.now());
    return orderRepository.save(order);
  }

  /** Verify compensation path — order goes back to OPEN so the cashier can adjust it and retry. */
  @Transactional
  public Order compensateToOpen(Long orderId, String reason) {
    Order order = getOrder(orderId);
    order.setStatus(OrderStatus.OPEN);
    order.setFailureReason(reason);
    return orderRepository.save(order);
  }

  /**
   * Payment compensation path — the stock commit failed (or timed out), but the earlier reservation
   * is still legitimately held, so this goes back to CONFIRMED (not OPEN) with a failureReason: the
   * cashier can just retry payment, no need to re-verify stock.
   */
  @Transactional
  public Order revertToConfirmed(Long orderId, String reason) {
    Order order = getOrder(orderId);
    order.setStatus(OrderStatus.CONFIRMED);
    order.setFailureReason(reason);
    return orderRepository.save(order);
  }

  private void requireOpen(Order order) {
    if (order.getStatus() != OrderStatus.OPEN) {
      throw new BusinessRuleException(
          "Order cannot be checked out from status " + order.getStatus() + ": " + order.getId());
    }
  }
}
