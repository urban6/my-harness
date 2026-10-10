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

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCouponIdAndUserIdAndStatusIn(Long couponId, String userId, Collection<OrderStatus> statuses);

    /** 만료 대상: 결제 대기 중이고 기한이 지났으며 PG 호출이 진행 중이지 않은 주문. */
    @Query("""
            select o.id from PurchaseOrder o
            where o.status = com.example.order.order.OrderStatus.PENDING_PAYMENT
              and o.expiresAt <= :now
              and (o.gatewayStartedAt is null or o.gatewayStartedAt <= :staleBefore)
            order by o.expiresAt
            """)
    List<Long> findExpiredIds(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore, Pageable page);
}
