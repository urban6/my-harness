package com.example.order.service;

import com.example.order.domain.Coupon;
import com.example.order.domain.Product;
import com.example.order.repository.CouponRepository;
import com.example.order.repository.ProductRepository;
import com.example.order.web.ApiException;
import com.example.order.web.Dtos.CouponRequest;
import com.example.order.web.Dtos.CouponResponse;
import com.example.order.web.Dtos.ProductRequest;
import com.example.order.web.Dtos.ProductResponse;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final CouponRepository coupons;
    private final EntityManager em;

    public CatalogService(ProductRepository products, CouponRepository coupons, EntityManager em) {
        this.products = products;
        this.coupons = coupons;
        this.em = em;
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest req) {
        return ProductResponse.from(products.saveAndFlush(new Product(req.name(), req.price(), req.stock())));
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(long id) {
        return products.findById(id).map(ProductResponse::from).orElseThrow(() -> ApiException.notFound("product", id));
    }

    @Transactional
    public CouponResponse createCoupon(CouponRequest req) {
        if (coupons.existsById(req.code())) {
            throw ApiException.conflict("coupon " + req.code() + " already exists");
        }
        Coupon coupon = new Coupon(req.code(), req.type(), req.value(),
                req.minOrderAmount() == null ? 0 : req.minOrderAmount(), req.maxDiscountAmount(),
                req.totalQuantity(), req.validFrom(), req.validUntil());
        try {
            em.persist(coupon);
            em.flush();
            return CouponResponse.from(coupon);
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException e) {
            throw ApiException.conflict("coupon " + req.code() + " already exists");
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse getCoupon(String code) {
        return coupons.findById(code).map(CouponResponse::from).orElseThrow(() -> ApiException.notFound("coupon", code));
    }
}
