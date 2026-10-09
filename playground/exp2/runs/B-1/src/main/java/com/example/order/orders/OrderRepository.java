package com.example.order.orders;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderQueryRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /** 만료 대상을 잠그되, 결제 처리 중(잠김)인 주문은 건너뛴다. */
    @Query(value = """
            select * from orders
            where id = :id and status = 'PENDING_PAYMENT' and expires_at <= :now
            for update skip locked
            """, nativeQuery = true)
    Optional<Order> findExpirableForUpdate(@Param("id") Long id, @Param("now") Instant now);

    @Query("""
            select o.id from Order o
            where o.status = com.example.order.orders.OrderStatus.PENDING_PAYMENT and o.expiresAt <= :now
            order by o.expiresAt
            """)
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Limit limit);

    @Query("""
            select count(o) > 0 from Order o
            where o.userId = :userId and o.couponCode = :couponCode
              and o.status in (com.example.order.orders.OrderStatus.PENDING_PAYMENT,
                               com.example.order.orders.OrderStatus.PAID,
                               com.example.order.orders.OrderStatus.SHIPPED,
                               com.example.order.orders.OrderStatus.DELIVERED)
            """)
    boolean existsActiveByUserIdAndCouponCode(@Param("userId") String userId, @Param("couponCode") String couponCode);
}
