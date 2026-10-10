package com.example.order.product;

import com.example.order.common.error.BusinessException;
import com.example.order.common.error.ErrorCode;
import com.example.order.product.dto.CreateProductRequest;
import com.example.order.product.dto.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest req) {
        Product saved = productRepository.saveAndFlush(new Product(req.name(), req.price(), req.stock()));
        // reserved=0 은 생성자 값 그대로이므로 재조회 없이 응답한다
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return productRepository.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "상품을 찾을 수 없습니다: id=" + id));
    }
}
