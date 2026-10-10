package com.example.order.coupon;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /** 선착순 수량 제한을 조건부 UPDATE로 원자적으로 지킨다. 0이면 소진. */
    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount + 1 where c.code = :code and c.usedCount < c.totalQuantity")
    int use(@Param("code") String code);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount - 1 where c.code = :code and c.usedCount > 0")
    int release(@Param("code") String code);
}
