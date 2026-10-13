package com.example.order.order;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    @Override
    Page<Order> findAll(Pageable pageable);

    /** ORDERED 상태일 때만 CANCELLED로 전이한다. 영향 행 0이면 이미 취소됨. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE orders SET status = 'CANCELLED' WHERE id = :id AND status = 'ORDERED'",
            nativeQuery = true)
    int cancelIfOrdered(@Param("id") Long id);
}
