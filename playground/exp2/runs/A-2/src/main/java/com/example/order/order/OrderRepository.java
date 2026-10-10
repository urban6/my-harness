package com.example.order.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdate(long id);

    @Query("""
            select count(o) > 0 from Order o
            where o.userId = :userId and o.couponCode = :couponCode and o.status in :statuses
            """)
    boolean existsCouponUsage(String userId, String couponCode, Collection<OrderStatus> statuses);

    @Query("""
            select o.id from Order o
            where o.status = com.example.order.order.OrderStatus.PENDING_PAYMENT and o.expiresAt <= :now
            order by o.expiresAt
            limit 200
            """)
    List<Long> findExpiredPendingIds(Instant now);
}
