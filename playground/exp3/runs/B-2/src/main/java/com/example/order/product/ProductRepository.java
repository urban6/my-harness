package com.example.order.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 재고 변경은 조건부 UPDATE 한 문장으로 처리한다(읽고-검사하고-쓰기 경합 방지).
 * 반환값은 갱신된 행 수이며, 0이면 조건 불충족이다.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved + :qty where p.id = :id and p.stock - p.reserved >= :qty")
    int reserve(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved - :qty where p.id = :id and p.reserved >= :qty")
    int release(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :qty, p.reserved = p.reserved - :qty where p.id = :id and p.reserved >= :qty")
    int confirm(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :qty where p.id = :id")
    int restock(@Param("id") Long id, @Param("qty") int qty);
}
