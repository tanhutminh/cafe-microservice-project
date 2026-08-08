package com.cafe.inventoryservice.inbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, String> {

    /**
     * Claims the next batch of messages in a given status (PENDING for a normal poll, but the
     * poller also uses this for a message it just put back to PENDING after a failed attempt).
     * Row-locked with SKIP LOCKED (not just PESSIMISTIC_WRITE) so a second poller run - or a
     * second service instance, if this ever scales out - claims a different batch instead of
     * blocking on rows the first poller is already about to mark PROCESSING, mirroring
     * IngredientRepository.findAllByIdForUpdate's use of pessimistic locking for the same
     * "don't let two transactions act on the same row concurrently" reason.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT m FROM InboxMessage m WHERE m.status = :status ORDER BY m.receivedAt ASC")
    List<InboxMessage> lockNextByStatus(@Param("status") InboxStatus status, Pageable pageable);
}
