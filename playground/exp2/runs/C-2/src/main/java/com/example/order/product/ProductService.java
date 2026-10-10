package com.example.order.product;

import com.example.order.common.error.ProductNotFoundException;
import com.example.order.product.dto.CreateProductRequest;
import com.example.order.product.dto.ProductResponse;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final Clock clock;

    public ProductService(ProductRepository productRepository, Clock clock) {
        this.productRepository = productRepository;
        this.clock = clock;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product product = new Product(request.name(), request.price(), request.stock(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        return ProductResponse.from(productRepository.saveAndFlush(product));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ProductNotFoundException("상품 " + id + "을(를) 찾을 수 없습니다."));
    }
}
