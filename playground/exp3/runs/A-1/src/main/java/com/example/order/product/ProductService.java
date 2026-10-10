package com.example.order.product;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.order.common.ApiException;

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest req) {
        return ProductResponse.from(products.save(new Product(req.name().trim(), req.price(), req.stock())));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return products.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> ApiException.notFound("product " + id + " not found"));
    }
}
