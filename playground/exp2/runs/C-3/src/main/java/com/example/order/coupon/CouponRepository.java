package com.example.order.coupon;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /** 쿠폰 행 잠금 */
    @Query(value = "SELECT id FROM coupons WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Long lockById(@Param("id") long id);

    /** 0이면 소진 -> 409 COUPON_EXHAUSTED */
    @Modifying
    @Query(value = "UPDATE coupons SET used_count = used_count + 1 WHERE id = :id AND used_count < total_quantity",
            nativeQuery = true)
    int tryUse(@Param("id") long id);

    /** 사용 복원 */
    @Modifying
    @Query(value = "UPDATE coupons SET used_count = used_count - 1 WHERE code = :code AND used_count > 0",
            nativeQuery = true)
    int releaseUse(@Param("code") String code);
}
