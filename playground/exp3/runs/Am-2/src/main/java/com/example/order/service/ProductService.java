package com.example.order.service;

import com.example.order.domain.Product;
import com.example.order.repo.ProductRepository;
import com.example.order.web.ApiException;
import com.example.order.web.Dtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest r) {
        return ProductResponse.of(products.save(new Product(r.name(), r.price(), r.stock())));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return products.findById(id).map(ProductResponse::of).orElseThrow(() -> ApiException.notFound("Product", id));
    }
}
