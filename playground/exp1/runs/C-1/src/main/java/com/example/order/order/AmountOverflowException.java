package com.example.order.order;

public class AmountOverflowException extends RuntimeException {

    public AmountOverflowException() {
        super("주문 금액이 허용 범위를 초과했습니다.");
    }
}
