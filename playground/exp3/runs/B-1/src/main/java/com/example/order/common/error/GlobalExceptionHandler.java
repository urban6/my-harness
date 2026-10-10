package com.example.order.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.order.payment.PaymentGatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 RFC 9457 Problem Details 로 매핑한다.
 * 스프링 MVC 기본 예외(누락된 헤더, 타입 불일치, 파싱 실패 등)는 부모 클래스가 ProblemDetail 로 처리한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 검증에 실패했습니다.");
        pd.setTitle("Validation Failed");
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource Not Found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(CouponNotApplicableException.class)
    public ProblemDetail handleCoupon(CouponNotApplicableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Coupon Not Applicable", ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalid(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", ex.getMessage());
    }

    @ExceptionHandler(ArithmeticException.class)
    public ProblemDetail handleOverflow(ArithmeticException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request", "금액 계산 범위를 초과했습니다.");
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ProblemDetail handleGateway(PaymentGatewayException ex) {
        log.warn("결제 게이트웨이 호출 실패: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "Payment Gateway Error", "결제 게이트웨이 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("데이터 무결성 위반", ex);
        return problem(HttpStatus.CONFLICT, "Conflict", "요청이 기존 데이터와 충돌합니다.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("처리되지 않은 예외", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "예상치 못한 오류가 발생했습니다.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
