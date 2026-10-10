package com.example.order.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * M1: every /api request needs a valid X-Tenant-Id. Runs before argument binding, so a bad or missing header is a 400
 * before any body parsing or lookup (C3). Controllers can then read the header as-is.
 */
public class TenantInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Tenant-Id";
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{1,30}");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant = request.getHeader(HEADER);
        if (tenant == null || !FORMAT.matcher(tenant).matches()) {
            throw ApiException.validation("X-Tenant-Id header is required (1-30 characters: a-z, 0-9, -)");
        }
        return true;
    }
}
