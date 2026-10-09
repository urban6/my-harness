package com.example.order.product;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private final ProductRepository repository;
    private final Clock clock;

    public ProductService(ProductRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest req) {
        Product p = repository.saveAndFlush(new Product(req.name(), req.price(), req.stock(), Times.now(clock)));
        return ProductResponse.from(p);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return repository.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "product " + id + " not found"));
    }
}
