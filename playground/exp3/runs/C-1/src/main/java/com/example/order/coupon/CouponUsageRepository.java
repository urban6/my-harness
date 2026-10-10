package com.example.order.coupon;

import com.example.order.common.Times;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Atomic conditional UPDATEs on coupons.used_count (02_db_design.md section 3 patterns 4-5). */
@Repository
public class CouponUsageRepository {

    private final JdbcClient jdbc;

    public CouponUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** (4) false when exhausted or outside the valid period. */
    public boolean tryUse(long couponId, Instant now) {
        return jdbc.sql("UPDATE coupons SET used_count = used_count + 1 "
                        + "WHERE id = :id AND used_count < total_quantity "
                        + "AND valid_from <= :now AND valid_until >= :now")
                .param("id", couponId).param("now", Times.ts(now))
                .update() == 1;
    }

    /** (5) give a used quantity back. */
    public boolean release(long couponId) {
        return jdbc.sql("UPDATE coupons SET used_count = used_count - 1 WHERE id = :id AND used_count > 0")
                .param("id", couponId).update() == 1;
    }
}
