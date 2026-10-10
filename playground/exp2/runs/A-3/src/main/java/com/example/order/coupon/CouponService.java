package com.example.order.coupon;

import com.example.order.common.ApiException;
import com.example.order.common.ErrorCode;
import com.example.order.coupon.CouponDtos.CouponResponse;
import com.example.order.coupon.CouponDtos.CreateCouponRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import static com.example.order.common.Validations.inRange;
import static com.example.order.common.Validations.notNull;
import static com.example.order.common.Validations.require;

@Service
public class CouponService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{4,20}$");
    // PostgreSQL timestamptz 가 담을 수 있는 범위 안으로 제한한다
    private static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    private final CouponRepository coupons;

    public CouponService(CouponRepository coupons) {
        this.coupons = coupons;
    }

    public CouponResponse create(CreateCouponRequest request) {
        notNull(request, "body");
        notNull(request.code(), "code");
        require(CODE.matcher(request.code()).matches(), "code must be 4-20 uppercase letters or digits");
        notNull(request.type(), "type");
        CouponType type;
        try {
            type = CouponType.valueOf(request.type());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("type must be FIXED or RATE");
        }
        if (type == CouponType.FIXED) {
            inRange(request.value(), 1, Long.MAX_VALUE, "value");
        } else {
            inRange(request.value(), 1, 100, "value");
        }
        long minOrderAmount = request.minOrderAmount() == null ? 0 : request.minOrderAmount();
        require(minOrderAmount >= 0, "minOrderAmount must be 0 or greater");
        if (request.maxDiscountAmount() != null) {
            require(request.maxDiscountAmount() >= 1, "maxDiscountAmount must be 1 or greater");
        }
        inRange(request.totalQuantity(), 1, Integer.MAX_VALUE, "totalQuantity");
        Instant validFrom = parseTimestamp(request.validFrom(), "validFrom");
        Instant validUntil = parseTimestamp(request.validUntil(), "validUntil");
        require(validFrom.isBefore(validUntil), "validFrom must be before validUntil");

        if (coupons.existsByCode(request.code())) {
            throw duplicate(request.code());
        }
        Coupon coupon = new Coupon(request.code(), type, request.value(), minOrderAmount,
                request.maxDiscountAmount(), request.totalQuantity(),
                validFrom, request.validFrom(), validUntil, request.validUntil(), Instant.now());
        try {
            return CouponResponse.from(coupons.saveAndFlush(coupon));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 code 로 등록된 경우
            throw duplicate(request.code());
        }
    }

    @Transactional(readOnly = true)
    public CouponResponse get(String code) {
        return coupons.findByCode(code)
                .map(CouponResponse::from)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon " + code + " not found"));
    }

    private static ApiException duplicate(String code) {
        return new ApiException(ErrorCode.DUPLICATE_COUPON_CODE, "Coupon code " + code + " already exists");
    }

    private static Instant parseTimestamp(String value, String field) {
        notNull(value, field);
        Instant instant;
        try {
            instant = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.validation(field + " must be an ISO-8601 timestamp with offset");
        }
        require(!instant.isBefore(MIN_INSTANT) && !instant.isAfter(MAX_INSTANT), field + " is out of range");
        return instant;
    }
}
