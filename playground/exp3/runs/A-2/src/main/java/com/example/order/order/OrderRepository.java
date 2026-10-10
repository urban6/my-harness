package com.example.order.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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
    Optional<Order> findByIdForUpdate(@Param("id") long id);

    @Query(value = "select id from orders where status = 'PENDING_PAYMENT' and expires_at <= :now "
            + "order by expires_at limit 100", nativeQuery = true)
    List<Long> findDueIds(@Param("now") Instant now);

    /** 다른 트랜잭션(결제 진행 중 등)이 잠근 주문은 건너뛴다. */
    @Query(value = "select * from orders where id = :id and status = 'PENDING_PAYMENT' and expires_at <= :now "
            + "for update skip locked", nativeQuery = true)
    Optional<Order> lockIfDue(@Param("id") long id, @Param("now") Instant now);
}
