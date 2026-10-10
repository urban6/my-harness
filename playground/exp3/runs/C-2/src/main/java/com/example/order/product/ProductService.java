package com.example.order.product;

import com.example.order.common.Problems;
import com.example.order.common.TimeSupport;
import com.example.order.product.ProductDtos.CreateProductRequest;
import com.example.order.product.ProductDtos.ProductResponse;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final Clock clock;

    public ProductService(ProductRepository productRepository, Clock clock) {
        this.productRepository = productRepository;
        this.clock = clock;
    }

    public ProductResponse create(CreateProductRequest request) {
        Product saved = productRepository.save(new Product(request.name(), request.price(),
                request.stock().intValue(), TimeSupport.now(clock)));
        return ProductResponse.from(saved);
    }

    public ProductResponse get(long id) {
        return productRepository.findById(id).map(ProductResponse::from)
                .orElseThrow(() -> Problems.productNotFound(id));
    }
}
