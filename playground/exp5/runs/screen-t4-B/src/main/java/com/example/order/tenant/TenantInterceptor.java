package com.example.order.tenant;

import com.example.order.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** M1: every /api/ request needs a well-formed X-Tenant-Id header; controllers read it from a request attribute. */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Tenant-Id";
    public static final String ATTRIBUTE = "tenantId";

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{1,30}");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = request.getHeader(HEADER);
        if (tenantId == null || !FORMAT.matcher(tenantId).matches()) {
            throw ApiException.validation(HEADER + " header is required (1-30 characters of a-z, 0-9, '-')");
        }
        request.setAttribute(ATTRIBUTE, tenantId);
        return true;
    }
}
