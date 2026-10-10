package com.example.order.orders;

import com.example.order.common.ApiException;
import com.example.order.orders.OrderDtos.OrderPage;
import com.example.order.orders.OrderDtos.OrderResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * R9. (createdAt desc, id desc) 키셋 페이지네이션.
 * 커서는 직전 페이지 마지막 주문의 (createdAt, id) 이므로 사이에 새 주문이 생겨도 기존 주문이 밀리지 않는다.
 */
@Service
public class OrderQueryService {

    private final EntityManager em;

    public OrderQueryService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public OrderPage list(String userId, OrderStatus status, int size, String cursor) {
        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
        if (userId != null) {
            jpql.append(" and o.userId = :userId");
        }
        if (status != null) {
            jpql.append(" and o.status = :status");
        }
        if (after != null) {
            jpql.append(" and (o.createdAt < :cursorAt or (o.createdAt = :cursorAt and o.id < :cursorId))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");

        TypedQuery<Order> query = em.createQuery(jpql.toString(), Order.class);
        if (userId != null) {
            query.setParameter("userId", userId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (after != null) {
            query.setParameter("cursorAt", after.createdAt());
            query.setParameter("cursorId", after.id());
        }
        List<Order> rows = query.setMaxResults(size + 1).getResultList();

        boolean hasNext = rows.size() > size;
        List<Order> page = hasNext ? rows.subList(0, size) : rows;
        List<OrderResponse> content = new ArrayList<>(page.size());
        for (Order order : page) {
            content.add(OrderResponse.from(order));
        }
        String nextCursor = null;
        if (hasNext) {
            Order last = page.getLast();
            nextCursor = new Cursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new OrderPage(content, nextCursor);
    }

    record Cursor(Instant createdAt, long id) {

        String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
            String raw = "v1:" + micros + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split(":");
                if (parts.length != 3 || !parts[0].equals("v1")) {
                    throw new IllegalArgumentException("unknown cursor format");
                }
                long micros = Long.parseLong(parts[1]);
                long id = Long.parseLong(parts[2]);
                return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
            } catch (RuntimeException e) {
                throw ApiException.validation("cursor is invalid");
            }
        }
    }
}
