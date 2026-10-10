package com.example.order.orders;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderQueryRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM orders
                            WHERE user_id = :userId AND coupon_code = :code
                              AND status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED'))""",
            nativeQuery = true)
    boolean existsActiveCouponUsage(@Param("userId") String userId, @Param("code") String code);

    @Query(value = """
            SELECT id FROM orders
             WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now
             ORDER BY expires_at LIMIT :limit""", nativeQuery = true)
    List<Long> findExpiredIds(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM orders
             WHERE id = :id AND status = 'PENDING_PAYMENT' AND expires_at <= :now
             FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Optional<Order> lockExpiredForSweep(@Param("id") long id, @Param("now") Instant now);
}
