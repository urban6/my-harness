package com.example.order.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PayRequest(@NotBlank @Size(max = 255) String cardToken) {

    /** 실수로 로그에 찍혀도 카드 토큰이 노출되지 않도록 한다. */
    @Override
    public String toString() {
        return "PayRequest[cardToken=***]";
    }
}
