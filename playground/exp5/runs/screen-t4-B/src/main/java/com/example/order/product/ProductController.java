package com.example.order.product;

import com.example.order.common.ApiException;
import com.example.order.order.ExpiryService;
import com.example.order.tenant.TenantInterceptor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;
    private final ExpiryService expiry;

    public ProductController(ProductRepository products, ExpiryService expiry) {
        this.products = products;
        this.expiry = expiry;
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Min(1) @Max(10_000_000) Long price,
            @NotNull @Min(0) @Max(1_000_000) Long stock) {
    }

    public record ProductResponse(long id, String name, long price, long stock, long reserved, long available) {
        static ProductResponse from(Product p) {
            return new ProductResponse(p.id(), p.name(), p.price(), p.stock(), p.reserved(), p.available());
        }
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@RequestAttribute(TenantInterceptor.ATTRIBUTE) String tenantId,
            @Valid @RequestBody CreateProductRequest req) {
        Product saved = products.insert(tenantId, req.name(), req.price(), req.stock());
        return ResponseEntity.created(URI.create("/api/products/" + saved.id())).body(ProductResponse.from(saved));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@RequestAttribute(TenantInterceptor.ATTRIBUTE) String tenantId, @PathVariable long id) {
        expiry.expireDue();
        return products.find(tenantId, id).map(ProductResponse::from)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "product " + id + " not found"));
    }
}
