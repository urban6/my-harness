package com.example.order.product;

import com.example.order.common.ApiException;
import com.example.order.common.PathIds;
import com.example.order.common.Times;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    public record CreateProductRequest(String name, Long price, Long stock) {
    }

    public record ProductResponse(long id, String name, long price, int stock, int reserved, int available) {
        static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getReserved(), p.available());
        }
    }

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ProductResponse> create(@RequestBody CreateProductRequest req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 100) {
            throw ApiException.validation("name must be 1-100 characters and not blank");
        }
        if (req.price() == null || req.price() < 1 || req.price() > 10_000_000) {
            throw ApiException.validation("price must be between 1 and 10,000,000");
        }
        if (req.stock() == null || req.stock() < 0 || req.stock() > 1_000_000) {
            throw ApiException.validation("stock must be between 0 and 1,000,000");
        }
        Product saved = products.save(new Product(req.name(), req.price(), req.stock().intValue(), Times.now()));
        return ResponseEntity.created(URI.create("/api/products/" + saved.getId())).body(ProductResponse.of(saved));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ProductResponse get(@PathVariable String id) {
        long productId = PathIds.parse(id, "PRODUCT_NOT_FOUND", "Product");
        return products.findById(productId)
                .map(ProductResponse::of)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + id + " not found"));
    }
}
