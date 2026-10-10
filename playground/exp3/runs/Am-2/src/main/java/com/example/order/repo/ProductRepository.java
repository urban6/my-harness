package com.example.order.repo;

import com.example.order.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved + :qty where p.id = :id and p.stock - p.reserved >= :qty")
    int reserve(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved - :qty where p.id = :id and p.reserved >= :qty")
    int release(@Param("id") Long id, @Param("qty") int qty);

    /** Reserved units become sold: stock and reserved both drop. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :qty, p.reserved = p.reserved - :qty where p.id = :id and p.reserved >= :qty")
    int commit(@Param("id") Long id, @Param("qty") int qty);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :qty where p.id = :id")
    int restock(@Param("id") Long id, @Param("qty") int qty);
}
