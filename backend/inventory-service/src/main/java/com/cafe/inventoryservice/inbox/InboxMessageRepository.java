package com.cafe.inventoryservice.inbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, String> {

  /**
   * Row-locks the next batch of messages in the given status via SKIP LOCKED (not just
   * PESSIMISTIC_WRITE), so a concurrent claim - another poll cycle, or another service instance if
   * this ever scales out - gets a disjoint batch instead of blocking on rows already about to move
   * to PROCESSING. The status filter is a plain equality match: it returns whatever rows currently
   * hold that status, regardless of how they got there.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("SELECT m FROM InboxMessage m WHERE m.status = :status ORDER BY m.receivedAt ASC")
  List<InboxMessage> lockNextByStatus(@Param("status") InboxStatus status, Pageable pageable);
}
