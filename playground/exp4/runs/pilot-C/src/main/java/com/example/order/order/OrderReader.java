package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 읽기 전용 트랜잭션 조회. */
@Service
public class OrderReader {

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;

    public OrderReader(OrderRepository orderRepository, EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return orderRepository.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    /** 필터는 조건부 조합(null 바인딩 타입 추론 문제 회피). 정렬 created_at DESC, id DESC. */
    @Transactional(readOnly = true)
    public OrderPage list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        StringBuilder jpql = new StringBuilder("select o from OrderEntity o where 1 = 1");
        if (userId != null) {
            jpql.append(" and o.userId = :userId");
        }
        if (status != null) {
            jpql.append(" and o.status = :status");
        }
        if (cursor != null) {
            jpql.append(" and (o.createdAt < :cAt or (o.createdAt = :cAt and o.id < :cId))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");
        TypedQuery<OrderEntity> q = entityManager.createQuery(jpql.toString(), OrderEntity.class);
        if (userId != null) {
            q.setParameter("userId", userId);
        }
        if (status != null) {
            q.setParameter("status", status);
        }
        if (cursor != null) {
            q.setParameter("cAt", cursor.createdAt());
            q.setParameter("cId", cursor.id());
        }
        q.setMaxResults(size + 1);
        List<OrderEntity> rows = q.getResultList();
        boolean hasNext = rows.size() > size;
        List<OrderEntity> page = hasNext ? rows.subList(0, size) : rows;
        String next = null;
        if (hasNext) {
            OrderEntity last = page.get(page.size() - 1);
            next = new OrderCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new OrderPage(page.stream().map(OrderResponse::from).toList(), next);
    }

    static ApiException notFound(Long id) {
        return new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + id + " not found.");
    }
}
