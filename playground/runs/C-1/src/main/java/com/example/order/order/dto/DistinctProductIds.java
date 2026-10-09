package com.example.order.order.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** items 안에서 productId가 중복되면 위반. null 리스트·원소·productId는 건너뛴다(다른 제약이 보고). */
@Documented
@Constraint(validatedBy = DistinctProductIdsValidator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
public @interface DistinctProductIds {

    String message() default "productId는 중복될 수 없습니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
