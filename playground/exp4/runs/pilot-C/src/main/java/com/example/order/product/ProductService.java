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
    public ProductResponse create(ProductCreateRequest req) {
        Product saved = repository.save(new Product(req.name(), req.price(), req.stock(), Times.now(clock)));
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return repository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " not found."));
    }
}
