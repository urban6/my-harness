package com.example.order.product;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping("/api/products")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest req) {
        ProductResponse res = service.create(req);
        return ResponseEntity.created(URI.create("/api/products/" + res.id())).body(res);
    }

    @GetMapping("/api/products/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return service.get(id);
    }
}
