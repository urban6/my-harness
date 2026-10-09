package com.example.order.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now "
            + "ORDER BY expires_at, id LIMIT :limit", nativeQuery = true)
    List<Long> findExpirableIds(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT * FROM orders WHERE id = :id AND status = 'PENDING_PAYMENT' "
            + "AND expires_at <= :now FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Order> lockExpirable(@Param("id") Long id, @Param("now") Instant now);

    @Query("select count(o) > 0 from Order o where o.userId = :userId and o.couponCode = :code "
            + "and o.status in :active")
    boolean existsActiveCouponUse(@Param("userId") String userId, @Param("code") String code,
                                  @Param("active") Collection<OrderStatus> active);
}
