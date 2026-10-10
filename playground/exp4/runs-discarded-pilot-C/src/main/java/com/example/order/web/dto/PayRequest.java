package com.example.order.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** cardToken 은 DB/로그에 남기지 않는다. toString 도 가린다. */
public record PayRequest(@NotNull @NotBlank String cardToken) {

    @Override
    public String toString() {
        return "PayRequest[cardToken=***]";
    }
}
