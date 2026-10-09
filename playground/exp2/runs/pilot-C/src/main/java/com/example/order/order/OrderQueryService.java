package com.example.order.order;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {
    private final OrderRepository orderRepository;
    private final EntityManager em;

    public OrderQueryService(OrderRepository orderRepository, EntityManager em) {
        this.orderRepository = orderRepository;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(long id) {
        return orderRepository.findById(id).map(OrderResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "order " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public OrderPageResponse list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        StringBuilder jpql = new StringBuilder("select o from OrderEntity o where 1 = 1");
        if (userId != null) jpql.append(" and o.userId = :userId");
        if (status != null) jpql.append(" and o.status = :status");
        if (cursor != null) {
            jpql.append(" and (o.createdAt < :cAt or (o.createdAt = :cAt and o.id < :cId))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");
        TypedQuery<OrderEntity> q = em.createQuery(jpql.toString(), OrderEntity.class);
        if (userId != null) q.setParameter("userId", userId);
        if (status != null) q.setParameter("status", status);
        if (cursor != null) {
            q.setParameter("cAt", cursor.createdAt());
            q.setParameter("cId", cursor.id());
        }
        q.setMaxResults(size + 1);
        List<OrderEntity> rows = q.getResultList();
        boolean hasNext = rows.size() > size;
        List<OrderEntity> page = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext ? OrderCursor.of(page.get(page.size() - 1)).encode() : null;
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), next);
    }
}
