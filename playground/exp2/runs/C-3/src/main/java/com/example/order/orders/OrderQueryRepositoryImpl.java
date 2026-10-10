package com.example.order.orders;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;

public class OrderQueryRepositoryImpl implements OrderQueryRepository {

    private final EntityManager em;

    public OrderQueryRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<Order> findPage(String userId, OrderStatus status, CursorCodec.Cursor cursor, int limit) {
        StringBuilder jpql = new StringBuilder("select o from Order o where 1=1");
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

        TypedQuery<Order> q = em.createQuery(jpql.toString(), Order.class);
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
        q.setMaxResults(limit);
        return q.getResultList();
    }
}
