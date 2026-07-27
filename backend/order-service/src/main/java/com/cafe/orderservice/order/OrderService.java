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

    /** The order currently open (or mid-checkout) on a table, if any — used by the POS screen. */
    public Order getActiveOrderForTable(Long tableId) {
        return orderRepository.findActiveByTableId(tableId)
                .orElseThrow(() -> ResourceNotFoundException.of("Active order for table", tableId));
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

    @Transactional
    public Order cancel(Long orderId) {
        Order order = getOrder(orderId);
        if (order.getStatus() == OrderStatus.PAID) {
            throw new BusinessRuleException("Cannot cancel a paid order: " + orderId);
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setClosedAt(Instant.now());
        diningTableService.release(order.getTable().getId());
        return orderRepository.save(order);
    }

    /** Local half of checkout — see OrderCheckoutSaga for the saga state creation that joins this transaction. */
    @Transactional
    public Order checkout(Long orderId, String paymentMethod) {
        Order order = getOrder(orderId);
        requireOpen(order);
        if (order.getItems().isEmpty()) {
            throw new BusinessRuleException("Cannot checkout an empty order: " + orderId);
        }
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setPaymentMethod(paymentMethod);
        order.setFailureReason(null);
        return orderRepository.save(order);
    }

    /** Saga success path — called by OrderCheckoutSaga once inventory confirms the stock reservation. */
    @Transactional
    public Order markPaid(Long orderId) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.PAID);
        order.setClosedAt(Instant.now());
        diningTableService.release(order.getTable().getId());
        return orderRepository.save(order);
    }

    /** Saga compensation path — order goes back to OPEN so the cashier can adjust it and retry. */
    @Transactional
    public Order compensateToOpen(Long orderId, String reason) {
        Order order = getOrder(orderId);
        order.setStatus(OrderStatus.OPEN);
        order.setFailureReason(reason);
        return orderRepository.save(order);
    }

    private void requireOpen(Order order) {
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new BusinessRuleException("Order is not open: " + order.getId());
        }
    }
}
