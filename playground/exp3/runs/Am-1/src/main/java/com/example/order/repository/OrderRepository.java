package com.example.order.repository;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
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

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdate(@Param("id") Long id);

    @Query("select o.id from Order o where o.status = :status and o.expiresAt <= :now order by o.expiresAt")
    List<Long> findDueIds(@Param("status") OrderStatus status, @Param("now") Instant now, Pageable page);
}
