package com.example.order.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * M1: every request under /api/ must carry a valid X-Tenant-Id. It runs before handler lookup, body parsing and
 * validation, so a missing/malformed header is the first 400 (C3). The error goes through the regular exception
 * resolvers so it is the same Problem Details as every other error. The validated tenant is exposed as a request
 * attribute ({@link #ATTRIBUTE}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant-Id";
    public static final String ATTRIBUTE = "tenantId";
    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9-]{1,30}$");

    private final HandlerExceptionResolver resolver;

    public TenantFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = request.getHeader(HEADER);
        if (tenantId == null || !FORMAT.matcher(tenantId).matches()) {
            resolver.resolveException(request, response, null,
                    ApiException.validation(HEADER + " header is required (1-30 lowercase letters, digits, '-')"));
            return;
        }
        request.setAttribute(ATTRIBUTE, tenantId);
        chain.doFilter(request, response);
    }
}
