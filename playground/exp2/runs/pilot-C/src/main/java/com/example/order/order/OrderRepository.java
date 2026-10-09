package com.example.order.order;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> lockById(@Param("id") long id);

    @Query("select count(o) > 0 from OrderEntity o where o.couponCode = :code and o.userId = :userId "
            + "and o.status in :statuses")
    boolean existsInUse(@Param("code") String code, @Param("userId") String userId,
                        @Param("statuses") Collection<OrderStatus> statuses);

    @Query("select o.id from OrderEntity o where o.status = :status and o.expiresAt <= :now "
            + "order by o.expiresAt, o.id")
    List<Long> findDueIds(@Param("status") OrderStatus status, @Param("now") OffsetDateTime now, Pageable pageable);

    @Query(value = "select * from orders where id = :id and status = 'PENDING_PAYMENT' and expires_at <= :now "
            + "for update skip locked", nativeQuery = true)
    Optional<OrderEntity> lockDueSkipLocked(@Param("id") long id, @Param("now") OffsetDateTime now);
}
