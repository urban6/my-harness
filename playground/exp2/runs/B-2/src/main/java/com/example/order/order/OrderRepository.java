package com.example.order.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findWithLockById(Long id);

    boolean existsByUserIdAndCouponCodeAndStatusIn(String userId, String couponCode, Collection<OrderStatus> statuses);

    @Query(value = """
            select id from orders
             where status = 'PENDING_PAYMENT' and expires_at <= :now
             order by expires_at
             limit :limit
            """, nativeQuery = true)
    List<Long> findExpiredPendingIds(@Param("now") Instant now, @Param("limit") int limit);

    /** 결제 처리 중(잠김)인 주문은 건너뛴다 — 결제 쪽이 끝난 뒤 다음 주기에 다시 본다. */
    @Query(value = """
            select * from orders
             where id = :id and status = 'PENDING_PAYMENT' and expires_at <= :now
             for update skip locked
            """, nativeQuery = true)
    Optional<Order> lockIfExpiredPending(@Param("id") Long id, @Param("now") Instant now);
}
