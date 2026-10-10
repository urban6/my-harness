package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.Times;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * 주문 조회 파사드. 트랜잭션을 갖지 않는다.
 * 단건 조회는 만료 지연 평가를 수행한다(스케줄러 지연과 무관하게 정확성 보장).
 */
@Service
public class OrderQueryService {

    private final OrderReader reader;
    private final OrderExpiryService expiryService;
    private final Clock clock;

    public OrderQueryService(OrderReader reader, OrderExpiryService expiryService, Clock clock) {
        this.reader = reader;
        this.expiryService = expiryService;
        this.clock = clock;
    }

    public OrderResponse get(Long id) {
        OrderResponse response = reader.get(id);
        if (response.status() == OrderStatus.PENDING_PAYMENT
                && !response.expiresAt().toInstant().isAfter(Times.now(clock))) {
            expiryService.expireOne(id); // 결제 진행 중이면 no-op
            return reader.get(id);
        }
        return response;
    }

    public OrderPage list(String userId, String status, Integer size, String cursor) {
        if (userId != null && userId.isBlank()) {
            throw ApiException.validation("userId must not be blank.");
        }
        OrderStatus parsedStatus = null;
        if (status != null) {
            try {
                parsedStatus = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("status is not a valid order status.");
            }
        }
        int pageSize = size == null ? 20 : size;
        if (pageSize < 1 || pageSize > 100) {
            throw ApiException.validation("size must be between 1 and 100.");
        }
        OrderCursor parsedCursor = cursor == null ? null : OrderCursor.decode(cursor);
        return reader.list(userId, parsedStatus, pageSize, parsedCursor);
    }
}
