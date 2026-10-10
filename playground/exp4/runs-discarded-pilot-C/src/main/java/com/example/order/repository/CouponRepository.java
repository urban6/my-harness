package com.example.order.repository;

import com.example.order.domain.Coupon;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /** SELECT ... FOR UPDATE. 같은 쿠폰의 주문 생성을 직렬화한다 (R10.2, R10.3). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.code = :code")
    Optional<Coupon> findLockedByCode(@Param("code") String code);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount + 1 where c.id = :id and c.usedCount < c.totalQuantity")
    int increaseUsed(@Param("id") long id);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount - 1 where c.id = :id and c.usedCount > 0")
    int decreaseUsed(@Param("id") long id);
}
