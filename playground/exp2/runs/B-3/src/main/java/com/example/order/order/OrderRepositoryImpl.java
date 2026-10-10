package com.example.order.order;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;

class OrderRepositoryImpl implements OrderRepositoryCustom {

    private final EntityManager entityManager;

    OrderRepositoryImpl(EntityManager entityManager) {
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
            jpql.append(" and (o.createdAt < :cursorCreatedAt or (o.createdAt = :cursorCreatedAt and o.id < :cursorId))");
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
            query.setParameter("cursorCreatedAt", after.createdAt());
            query.setParameter("cursorId", after.id());
        }
        return query.setMaxResults(limit).getResultList();
    }
}
