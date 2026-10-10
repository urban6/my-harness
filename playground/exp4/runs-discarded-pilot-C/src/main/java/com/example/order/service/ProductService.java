package com.example.order.service;

import com.example.order.config.AppClock;
import com.example.order.domain.Product;
import com.example.order.repository.ProductRepository;
import com.example.order.web.dto.ProductCreateRequest;
import com.example.order.web.dto.ProductResponse;
import com.example.order.web.error.ApiException;
import com.example.order.web.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final AppClock clock;

    public ProductService(ProductRepository products, AppClock clock) {
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest req) {
        Product saved = products.saveAndFlush(new Product(req.name(), req.price(), req.stock(), clock.now()));
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return products.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " not found."));
    }
}
