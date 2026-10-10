package com.example.order.product;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.product.ProductDtos.CreateProductRequest;
import com.example.order.product.ProductDtos.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.example.order.common.Validations.inRange;
import static com.example.order.common.Validations.notNull;
import static com.example.order.common.Validations.text;

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        notNull(request, "body");
        text(request.name(), 100, "name");
        inRange(request.price(), 1, 10_000_000, "price");
        inRange(request.stock(), 0, 1_000_000, "stock");
        Product saved = products.save(new Product(request.name(), request.price(), request.stock(), Instant.now()));
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return products.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " not found"));
    }
}
