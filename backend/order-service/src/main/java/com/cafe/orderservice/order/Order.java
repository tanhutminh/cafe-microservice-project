package com.cafe.orderservice.order;

import com.cafe.orderservice.table.DiningTable;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Order {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "table_id", nullable = false)
  private DiningTable table;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private OrderStatus status;

  @Column(name = "payment_method", length = 30)
  private String paymentMethod;

  @Column(name = "failure_reason", length = 500)
  private String failureReason;

  @Builder.Default
  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderItem> items = new ArrayList<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  /**
   * When the order's table was released (or {@code null} if the table it's on hasn't been released
   * since this order started). Distinct from {@link #closedAt}: reaching PAID doesn't release the
   * table by itself - staff do that as a separate step - so a PAID order can sit with {@code
   * closedAt} set for a while before {@code releasedAt} is. CANCELLED, by contrast, releases the
   * table automatically as part of cancelling, so both are set within the same transaction.
   */
  @Column(name = "released_at")
  private Instant releasedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public void addItem(OrderItem item) {
    item.setOrder(this);
    items.add(item);
  }
}
