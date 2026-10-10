package com.example.order.product;

import com.example.order.common.error.NotFoundException;
import com.example.order.product.dto.CreateProductRequest;
import com.example.order.product.dto.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product saved = productRepository.save(new Product(request.name(), request.price(), request.stock()));
        return ProductResponse.from(saved);
    }

    public ProductResponse getById(long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new NotFoundException("상품을 찾을 수 없습니다: id=" + id));
    }
}
