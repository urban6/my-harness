package com.example.order.repo;

import com.example.order.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdate(@Param("id") Long id);

    @Query("select o.id from Order o where o.status = com.example.order.domain.OrderStatus.PENDING_PAYMENT and o.expiresAt <= :now")
    List<Long> findExpiredPendingIds(@Param("now") Instant now);
}
