package com.example.order.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select count(o) > 0 from Order o
            where o.userId = :userId and o.couponCode = :couponCode and o.status in :statuses
            """)
    boolean existsByUserAndCouponInStatuses(@Param("userId") String userId,
                                            @Param("couponCode") String couponCode,
                                            @Param("statuses") Collection<OrderStatus> statuses);

    @Query("""
            select o.id from Order o
            where o.status = com.example.order.order.OrderStatus.PENDING_PAYMENT and o.expiresAt <= :now
            order by o.expiresAt
            """)
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Pageable pageable);
}
