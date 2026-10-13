package com.example.order.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 재고가 충분할 때만 차감한다. 영향 행 0이면 재고 부족. */
    @Modifying
    @Query(value = "UPDATE products SET stock = stock - :quantity WHERE id = :id AND stock >= :quantity",
            nativeQuery = true)
    int decreaseStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying
    @Query(value = "UPDATE products SET stock = stock + :quantity WHERE id = :id", nativeQuery = true)
    int increaseStock(@Param("id") Long id, @Param("quantity") int quantity);
}
