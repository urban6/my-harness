package com.example.order.web;

import com.example.order.service.ExpiryService;
import com.example.order.service.ProductService;
import com.example.order.web.dto.ProductCreateRequest;
import com.example.order.web.dto.ProductResponse;
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

    private final ProductService products;
    private final ExpiryService expiry;

    public ProductController(ProductService products, ExpiryService expiry) {
        this.products = products;
        this.expiry = expiry;
    }

    @PostMapping("/api/products")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        ProductResponse created = products.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @GetMapping("/api/products/{id}")
    public ProductResponse get(@PathVariable long id) {
        expiry.expireDue(); // 지연 만료: 방금 만료된 예약 반영
        return products.get(id);
    }
}
