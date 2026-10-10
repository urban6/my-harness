package com.example.order.coupon;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    /** Atomic use. 0 rows -> exhausted (time window was pre-checked with the same instant). */
    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount + 1 where c.code = :code "
            + "and c.usedCount < c.totalQuantity and c.validFrom <= :now and c.validUntil > :now")
    int use(@Param("code") String code, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query("update Coupon c set c.usedCount = c.usedCount - 1 where c.code = :code and c.usedCount > 0")
    int restore(@Param("code") String code);
}
