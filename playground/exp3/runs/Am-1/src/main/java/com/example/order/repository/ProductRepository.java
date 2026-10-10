package com.example.order.repository;

import com.example.order.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved + :q where p.id = :id and p.stock - p.reserved >= :q")
    int reserve(@Param("id") long id, @Param("q") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved - :q where p.id = :id")
    int release(@Param("id") long id, @Param("q") int quantity);

    /** Payment confirmed: the reserved units leave the shelf. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :q, p.reserved = p.reserved - :q where p.id = :id")
    int commit(@Param("id") long id, @Param("q") int quantity);

    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :q where p.id = :id")
    int restock(@Param("id") long id, @Param("q") int quantity);
}
