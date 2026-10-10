package com.example.order.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 상태 전이는 행 잠금 아래에서만 수행한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") long id);

    Optional<Order> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey);

    /** 같은 (userId, Idempotency-Key) 동시 요청을 직렬화한다. 트랜잭션 종료 시 자동 해제. */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:lockKey)) t", nativeQuery = true)
    int lockIdempotencyKey(@Param("lockKey") long lockKey);

    @Query("select count(o) > 0 from Order o where o.id = :id and o.status = :status and o.expiresAt <= :now")
    boolean isDue(@Param("id") long id, @Param("status") OrderStatus status, @Param("now") Instant now);

    @Query("select o.id from Order o where o.status = :status and o.expiresAt <= :now order by o.expiresAt")
    List<Long> findDueIds(@Param("status") OrderStatus status, @Param("now") Instant now, Pageable pageable);

    @Query("""
            select o from Order o
            where (:userId is null or o.userId = :userId)
              and (:status is null or o.status = :status)
              and (:cursor is null or o.id < :cursor)
            order by o.id desc
            """)
    List<Order> search(@Param("userId") Long userId, @Param("status") OrderStatus status,
                       @Param("cursor") Long cursor, Pageable pageable);
}
