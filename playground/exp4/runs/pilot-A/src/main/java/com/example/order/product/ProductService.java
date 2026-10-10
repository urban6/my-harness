package com.example.order.product;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Ids;
import com.example.order.common.Violations;
import com.example.order.product.ProductDtos.CreateProductRequest;
import com.example.order.product.ProductDtos.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest req) {
        Violations v = new Violations();
        v.check(req.name() != null && !req.name().isBlank() && req.name().length() <= 100,
                "name must be non-blank and at most 100 characters");
        v.check(req.price() != null && req.price() >= 1 && req.price() <= 10_000_000L,
                "price must be between 1 and 10000000");
        v.check(req.stock() != null && req.stock() >= 0 && req.stock() <= 1_000_000L,
                "stock must be between 0 and 1000000");
        v.throwIfAny();
        return ProductResponse.from(products.save(new Product(req.name(), req.price(), req.stock())));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String rawId) {
        long id = Ids.parse(rawId, ErrorCode.PRODUCT_NOT_FOUND);
        return products.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + rawId));
    }
}
