package com.example.order.ordering;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 락 순서의 첫 단계. 이 트랜잭션에서 해당 주문의 첫 접근이어야 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") long id);

    @Query("select o.id from Order o where o.status = :status and o.expiresAt <= :now order by o.expiresAt, o.id")
    List<Long> findExpiredIds(@Param("status") OrderStatus status, @Param("now") Instant now, Pageable pageable);

    /** 결제/취소가 락을 쥐고 있으면 건너뛴다 (empty). */
    @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Order> lockForExpiration(@Param("id") long id);

    @Query("select count(o) > 0 from Order o where o.couponCode = :code and o.userId = :userId and o.status in :statuses")
    boolean existsCouponUse(@Param("code") String code, @Param("userId") String userId,
            @Param("statuses") List<OrderStatus> statuses);

    default boolean existsActiveCouponUse(String code, String userId) {
        return existsCouponUse(code, userId, OrderStatus.COUPON_ACTIVE);
    }
}
