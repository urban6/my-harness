package com.example.order.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /** Atomic reservation. 0 rows -> insufficient stock. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved + :q where p.id = :id and p.stock - p.reserved >= :q")
    int reserve(@Param("id") long id, @Param("q") int quantity);

    /** Release a reservation (cancel/expire/decline). 0 rows -> invariant violation. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.reserved = p.reserved - :q where p.id = :id and p.reserved >= :q")
    int release(@Param("id") long id, @Param("q") int quantity);

    /** Payment approved: consume the reservation from real stock. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock - :q, p.reserved = p.reserved - :q "
            + "where p.id = :id and p.reserved >= :q and p.stock >= :q")
    int confirm(@Param("id") long id, @Param("q") int quantity);

    /** Refund: give stock back. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :q where p.id = :id")
    int restoreStock(@Param("id") long id, @Param("q") int quantity);

    @Query("select p.stock - p.reserved from Product p where p.id = :id")
    Integer findAvailable(@Param("id") long id);
}
