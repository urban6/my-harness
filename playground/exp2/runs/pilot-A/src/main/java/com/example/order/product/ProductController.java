package com.example.order.product;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.common.Times;
import com.example.order.product.ProductDtos.CreateProductRequest;
import com.example.order.product.ProductDtos.ProductResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;
    private final Clock clock;

    public ProductController(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product saved = products.save(
                new Product(request.name(), request.price(), request.stock(), Times.now(clock)));
        return ResponseEntity.created(URI.create("/api/products/" + saved.getId()))
                .body(ProductResponse.from(saved));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ProductResponse get(@PathVariable long id) {
        return products.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " not found"));
    }
}
