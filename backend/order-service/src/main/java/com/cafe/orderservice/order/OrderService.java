package com.cafe.orderservice.order;

import com.cafe.common.exception.BusinessRuleException;
import com.cafe.common.exception.ResourceNotFoundException;
import com.cafe.orderservice.client.MenuServiceClient;
import com.cafe.orderservice.client.dto.MenuItemDetails;
import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.DiningTableService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final DiningTableService diningTableService;
    private final MenuServiceClient menuServiceClient;

    public OrderService(OrderRepository orderRepository, DiningTableService diningTableService,
                         MenuServiceClient menuServiceClient) {
        this.orderRepository = orderRepository;
        this.diningTableService = diningTableService;
        this.menuServiceClient = menuServiceClient;
    }

    public Order getOrder(Long id) {
        return orderRepository.findByIdWithDetails(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Order", id));
    }

    /**
     * The order currently holding a table (open, mid-checkout, or paid-but-not-yet-released) —
     * used by the POS screen when staff click an OCCUPIED table.
     */
    public Order getCurrentOrderForTable(Long tableId) {
        return orderRepository.findCurrentByTableId(tableId)
                .orElseThrow(() -> ResourceNotFoundException.of("Current order for table", tableId));
    }

    @Transactional
    public Order createOrder(Long tableId) {
        DiningTable table = diningTableService.findById(tableId);
        diningTableService.occupy(tableId);
        Order order = Order.builder()
                .table(table)
                .status(OrderStatus.OPEN)
                .build();
        return orderRepository.save(order);
    }

    @Transactional
    public Order addItem(Long orderId, Long menuItemId, int quantity) {
        Order order = getOrder(orderId);
        requireOpen(order);

        MenuItemDetails details = menuServiceClient.findMenuItem(menuItemId);
        if (!details.available()) {
            throw new BusinessRuleException("Menu item is not available: " + details.name());
        }

        order.getItems().stream()
                .filter(item -> item.getMenuItemId().equals(menuItemId))
                .findFirst()
                .ifPresentOrElse(
                        existing -> existing.setQuantity(existing.getQuantity() + quantity),
                        () -> order.addItem(OrderItem.builder()
                                .menuItemId(details.id())
                                .nameSnapshot(details.name())
                                .priceSnapshot(details.price())
                                .quantity(quantity)
                                .build())
                );
        return orderRepository.save(order);
    }

    @Transactional
    public Order removeItem(Long orderId, Long orderItemId) {
        Order order = getOrder(orderId);
        requireOpen(order);
        boolean removed = order.getItems().removeIf(item -> item.getId().equals(orderItemId));
        if (!removed) {
            throw ResourceNotFoundException.of("OrderItem", orderItemId);
        }
        return orderRepository.save(order);
    }

    /** Sets a line's quantity directly (min 1 - use removeItem to take it down to 0 instead). */
    @Transactional
    public Order updateItemQuantity(Long orderId, Long orderItemId, int quantity) {
        Order order = getOrder(orderId);
        requireOpen(order);
        OrderItem item = order.getItems().stream()
                .filter(i -> i.getId().equals(orderItemId))
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("OrderItem", orderItemId));
        item.setQuantity(quantity);
        return orderRepository.save(order);
    }

    @Transactional
    public Order cancel(Long orderId) {
        Order order = getOrder(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            throw new BusinessRuleException("Cannot cancel a paid order: " + orderId);
        }
        if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION || order.getStatus() == OrderStatus.PAYMENT_PENDING) {
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
     * Moves an order to a different table (e.g. the party relocates but keeps the same tab).
     * Not allowed while either saga leg is in flight (PENDING_CONFIRMATION / PAYMENT_PENDING)
     * since the saga tracks the order, not the table, and a mid-flight move could race with
     * the reply.
     */
    @Transactional
    public Order moveTable(Long orderId, Long newTableId) {
        Order order = getOrder(orderId);
        if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION || order.getStatus() == OrderStatus.PAYMENT_PENDING) {
            throw new BusinessRuleException("Cannot move table while a saga step is in progress: " + orderId);
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

    /** Verify leg, local half — see OrderCheckoutSaga for the saga state creation that joins this transaction. */
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
     * Verify success path — called by OrderCheckoutSaga once inventory confirms the stock
     * hold. Stock is reserved but no payment has been taken yet; the cashier still has to call
     * startPayment separately via the /pay endpoint.
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
            throw new BusinessRuleException("Order must be verified before payment: " + orderId);
        }
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        order.setPaymentMethod(paymentMethod);
        order.setFailureReason(null);
        return orderRepository.save(order);
    }

    /**
     * Payment success path — called by OrderCheckoutSaga once inventory confirms the stock
     * commit. Deliberately does NOT release the table: paying doesn't mean the party has
     * left (pay-first-then-dine is a valid flow here). Staff frees the table explicitly via
     * DiningTableService.release() once it's actually empty.
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
     * Payment compensation path — the stock commit failed (or timed out), but the earlier
     * reservation is still legitimately held, so this goes back to CONFIRMED (not OPEN) with a
     * failureReason: the cashier can just retry payment, no need to re-verify stock.
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
            throw new BusinessRuleException("Order is not open: " + order.getId());
        }
    }
}
