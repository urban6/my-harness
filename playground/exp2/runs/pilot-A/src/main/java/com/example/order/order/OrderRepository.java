package com.example.order.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /** 다른 트랜잭션(결제·취소 진행 중)이 잡고 있는 주문은 건너뛴다. */
    @Query(value = "SELECT * FROM orders WHERE id = :id AND status = 'PENDING_PAYMENT' FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Order> lockPendingSkipLocked(@Param("id") Long id);

    @Query("""
            select count(o) > 0 from Order o
             where o.userId = :userId and o.couponCode = :couponCode and o.status in :statuses""")
    boolean existsByUserAndCouponInStatuses(@Param("userId") String userId,
                                            @Param("couponCode") String couponCode,
                                            @Param("statuses") Collection<OrderStatus> statuses);

    @Query("""
            select o.id from Order o
             where o.status = com.example.order.order.OrderStatus.PENDING_PAYMENT and o.expiresAt <= :now
             order by o.expiresAt""")
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Limit limit);
}
