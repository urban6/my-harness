package com.example.order.web;

import com.example.order.service.ProductService;
import com.example.order.web.Dtos.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest req) {
        ProductResponse p = service.create(req);
        return ResponseEntity.created(URI.create("/api/products/" + p.id())).body(p);
    }

    @GetMapping("/{id}")
    ProductResponse get(@PathVariable Long id) {
        return service.get(id);
    }
}
