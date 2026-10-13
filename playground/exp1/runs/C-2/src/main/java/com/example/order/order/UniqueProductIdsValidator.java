package com.example.order.order;

import com.example.order.order.dto.OrderItemRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UniqueProductIdsValidator implements ConstraintValidator<UniqueProductIds, List<OrderItemRequest>> {

    @Override
    public boolean isValid(List<OrderItemRequest> items, ConstraintValidatorContext context) {
        if (items == null) {
            return true;
        }
        Set<Long> seen = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (item == null || item.productId() == null) {
                continue; // 다른 제약이 보고한다
            }
            if (!seen.add(item.productId())) {
                return false;
            }
        }
        return true;
    }
}
