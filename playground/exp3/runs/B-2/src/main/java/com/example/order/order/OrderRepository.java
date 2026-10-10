package com.example.order.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    /** 상태 전이 직렬화를 위한 행 잠금(SELECT ... FOR UPDATE). 트랜잭션 안에서 처음 로드할 때만 최신 상태를 보장한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findWithLockById(Long id);

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    boolean existsByIdAndStatusAndExpiresAtLessThanEqual(Long id, OrderStatus status, Instant now);

    List<OrderIdOnly> findTop100ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(OrderStatus status, Instant now);

    /** 같은 (사용자, 멱등 키) 요청을 트랜잭션 종료까지 직렬화한다. */
    @Query(value = "select cast(pg_advisory_xact_lock(hashtextextended(:key, 0)) as text)", nativeQuery = true)
    String lockIdempotencyKey(@Param("key") String key);

    interface OrderIdOnly {
        Long getId();
    }
}
