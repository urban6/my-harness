package com.example.order.common.error;

import java.util.List;

/** DataIntegrityViolationException 원인 체인에서 위반된 제약(인덱스) 이름을 찾는다. */
public final class ConstraintNames {

    public static final String UQ_COUPONS_CODE = "uq_coupons_code";
    public static final String UX_ORDERS_ACTIVE_USER_COUPON = "ux_orders_active_user_coupon";

    private static final List<String> KNOWN = List.of(UQ_COUPONS_CODE, UX_ORDERS_ACTIVE_USER_COUPON);

    private ConstraintNames() {
    }

    public static String of(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                String name = cve.getConstraintName();
                int dot = name.lastIndexOf('.');
                return dot >= 0 ? name.substring(dot + 1) : name;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null) {
                for (String k : KNOWN) {
                    if (msg.contains(k)) {
                        return k;
                    }
                }
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
