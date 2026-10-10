package com.example.order.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    /** SELECT ... FOR UPDATE: every state transition happens under this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") Long id);

    /** Locks the order only if it is due for expiry and not locked by someone else (sweeper / lazy GET). */
    @Query(value = "SELECT * FROM orders WHERE id = :id AND status = 'PENDING_PAYMENT' AND expires_at <= :now "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<PurchaseOrder> lockDueSkipLocked(@Param("id") Long id, @Param("now") Instant now);

    @Query("select o.id from PurchaseOrder o where o.status = :status and o.expiresAt <= :now "
            + "order by o.expiresAt, o.id")
    List<Long> findDueIds(@Param("status") OrderStatus status, @Param("now") Instant now, Pageable pageable);
}
