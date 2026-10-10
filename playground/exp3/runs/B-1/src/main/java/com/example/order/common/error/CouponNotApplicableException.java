package com.example.order.common.error;

public class CouponNotApplicableException extends RuntimeException {

    public CouponNotApplicableException(String message) {
        super(message);
    }
}
