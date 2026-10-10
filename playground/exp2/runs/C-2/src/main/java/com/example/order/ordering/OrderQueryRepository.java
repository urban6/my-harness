package com.example.order.ordering;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 키셋 페이지네이션용 동적 쿼리 (필터가 없으면 조건을 뺀다). */
@Repository
public class OrderQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /** limit개를 (createdAt desc, id desc) 순으로 읽는다. */
    public List<Order> findPage(String userId, OrderStatus status, OrderCursor cursor, int limit) {
        StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
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
        TypedQuery<Order> query = em.createQuery(jpql.toString(), Order.class);
        if (userId != null) {
            query.setParameter("userId", userId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (cursor != null) {
            query.setParameter("cAt", cursor.createdAt());
            query.setParameter("cId", cursor.id());
        }
        return query.setMaxResults(limit).getResultList();
    }
}
