package com.example.order.service;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.repository.OrderRepository;
import com.example.order.web.dto.OrderPageResponse;
import com.example.order.web.dto.OrderResponse;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 주문 조회·목록 (R3.5, R9). 호출 전에 ExpiryService#expireDue 가 실행되어야 한다 (컨트롤러가 호출). */
@Service
public class OrderQueryService {

    public record ListQuery(String userId, OrderStatus status, int size, CursorCodec.Cursor cursor) {
    }

    private final OrderRepository orders;

    @PersistenceContext
    private EntityManager em;

    public OrderQueryService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public OrderResponse get(long id) {
        return orders.findById(id).map(OrderResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND, "Order " + id + " not found."));
    }

    /** 키셋 페이지네이션: ORDER BY created_at DESC, id DESC, (created_at, id) < cursor, LIMIT size+1. */
    @Transactional(readOnly = true)
    public OrderPageResponse list(ListQuery q) {
        StringBuilder sql = new StringBuilder("select o.* from orders o where 1 = 1");
        if (q.userId() != null) {
            sql.append(" and o.user_id = :userId");
        }
        if (q.status() != null) {
            sql.append(" and o.status = :status");
        }
        if (q.cursor() != null) {
            sql.append(" and (o.created_at, o.id) < (:cursorAt, :cursorId)");
        }
        sql.append(" order by o.created_at desc, o.id desc");
        Query query = em.createNativeQuery(sql.toString(), Order.class);
        if (q.userId() != null) {
            query.setParameter("userId", q.userId());
        }
        if (q.status() != null) {
            query.setParameter("status", q.status().name());
        }
        if (q.cursor() != null) {
            query.setParameter("cursorAt", q.cursor().createdAt());
            query.setParameter("cursorId", q.cursor().id());
        }
        query.setMaxResults(q.size() + 1);
        @SuppressWarnings("unchecked")
        List<Order> rows = query.getResultList();
        boolean hasNext = rows.size() > q.size();
        List<Order> page = hasNext ? rows.subList(0, q.size()) : rows;
        String nextCursor = null;
        if (hasNext) {
            Order last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(last.getCreatedAt(), last.getId());
        }
        return new OrderPageResponse(page.stream().map(OrderResponse::from).toList(), nextCursor);
    }
}
