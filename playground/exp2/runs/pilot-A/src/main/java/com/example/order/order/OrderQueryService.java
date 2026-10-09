package com.example.order.order;

import com.example.order.order.OrderDtos.OrderPage;
import com.example.order.order.OrderDtos.OrderResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final EntityManager em;

    public OrderQueryService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public OrderPage list(String userId, OrderStatus status, int size, OrderCursor cursor) {
        StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
        if (userId != null) {
            jpql.append(" and o.userId = :userId");
        }
        if (status != null) {
            jpql.append(" and o.status = :status");
        }
        if (cursor != null) {
            jpql.append(" and (o.createdAt < :cursorCreatedAt"
                    + " or (o.createdAt = :cursorCreatedAt and o.id < :cursorId))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");

        TypedQuery<Order> query = em.createQuery(jpql.toString(), Order.class);
        if (userId != null) {
            query.setParameter("userId", userId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (cursor != null) {
            query.setParameter("cursorCreatedAt", cursor.createdAt());
            query.setParameter("cursorId", cursor.id());
        }
        List<Order> rows = query.setMaxResults(size + 1).getResultList();

        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = null;
        if (hasNext) {
            Order last = page.getLast();
            nextCursor = new OrderCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new OrderPage(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }
}
