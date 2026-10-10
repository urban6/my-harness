package com.example.order.product;

import com.example.order.common.Problems;
import com.example.order.common.Times;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final Clock clock;

    public ProductService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product saved = products.saveAndFlush(
                new Product(request.name(), request.price(), request.stock(), Times.now(clock)));
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return products.findById(id).map(ProductResponse::from).orElseThrow(() -> Problems.productNotFound(id));
    }
}
