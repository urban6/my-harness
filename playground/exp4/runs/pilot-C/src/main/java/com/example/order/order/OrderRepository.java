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

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** 주문 행 잠금 (락 순서의 첫 번째). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> lockById(@Param("id") Long id);

    boolean existsByUserIdAndCouponIdAndStatusIn(String userId, Long couponId, Collection<OrderStatus> statuses);

    /** 만료 스캔 (락 없음). PG 호출 표식이 유효한 주문은 제외. */
    @Query("""
            select o.id from OrderEntity o
             where o.status = com.example.order.order.OrderStatus.PENDING_PAYMENT
               and o.expiresAt <= :now
               and (o.gatewayInflightSince is null or o.gatewayInflightSince < :inflightCutoff)
             order by o.expiresAt asc
            """)
    List<Long> findExpiredIds(@Param("now") Instant now, @Param("inflightCutoff") Instant inflightCutoff,
                              Pageable pageable);
}
