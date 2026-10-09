package com.example.order.orders;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;

class OrderQueryRepositoryImpl implements OrderQueryRepository {

    private final EntityManager entityManager;

    OrderQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Order> findPage(String userId, OrderStatus status, OrderCursor after, int limit) {
        StringBuilder jpql = new StringBuilder("select o from Order o where 1 = 1");
        if (userId != null) {
            jpql.append(" and o.userId = :userId");
        }
        if (status != null) {
            jpql.append(" and o.status = :status");
        }
        if (after != null) {
            jpql.append(" and (o.createdAt < :createdAt or (o.createdAt = :createdAt and o.id < :id))");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");

        TypedQuery<Order> query = entityManager.createQuery(jpql.toString(), Order.class);
        if (userId != null) {
            query.setParameter("userId", userId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (after != null) {
            query.setParameter("createdAt", after.createdAt());
            query.setParameter("id", after.id());
        }
        return query.setMaxResults(limit).getResultList();
    }
}
