package com.example.order.repository;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
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

    /** 상태 전이용 조회: SELECT ... FOR UPDATE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<Order> findLockedById(@Param("id") long id);

    boolean existsByCouponIdAndUserIdAndStatusIn(Long couponId, String userId, Collection<OrderStatus> statuses);

    /** 만료 후보 id (락 없음). 진행 중 표지가 신선한 주문은 제외. */
    @Query("select o.id from PurchaseOrder o where o.status = :pending and o.expiresAt <= :now "
            + "and (o.gatewayCallStartedAt is null or o.gatewayCallStartedAt <= :staleBefore) "
            + "order by o.expiresAt")
    List<Long> findExpirableIds(@Param("pending") OrderStatus pending,
                                @Param("now") Instant now,
                                @Param("staleBefore") Instant staleBefore,
                                Pageable pageable);
}
