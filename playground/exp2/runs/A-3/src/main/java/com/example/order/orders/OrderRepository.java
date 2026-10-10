package com.example.order.orders;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") long id);

    /** 다른 트랜잭션(결제·취소)이 잡고 있으면 건너뛴다. */
    @Query(value = "select * from orders where id = :id for update skip locked", nativeQuery = true)
    Optional<Order> findByIdForUpdateSkipLocked(@Param("id") long id);

    @Query("""
            select count(o) > 0 from Order o
             where o.userId = :userId and o.couponCode = :couponCode and o.status in :statuses""")
    boolean existsByUserAndCouponInStatuses(@Param("userId") String userId,
                                            @Param("couponCode") String couponCode,
                                            @Param("statuses") Collection<OrderStatus> statuses);

    @Query("""
            select o.id from Order o
             where o.status = com.example.order.orders.OrderStatus.PENDING_PAYMENT and o.expiresAt <= :now
             order by o.expiresAt""")
    List<Long> findOverduePendingIds(@Param("now") Instant now, org.springframework.data.domain.Limit limit);
}
