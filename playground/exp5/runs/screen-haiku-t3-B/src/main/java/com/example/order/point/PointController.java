package com.example.order.point;

import com.example.order.common.ApiException;
import com.example.order.order.ExpiryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/points")
public class PointController {

    private final PointRepository points;
    private final ExpiryService expiry;

    public PointController(PointRepository points, ExpiryService expiry) {
        this.points = points;
        this.expiry = expiry;
    }

    public record GrantRequest(@NotNull @Min(1) @Max(10_000_000) Long amount) {
    }

    public record PointResponse(String userId, long balance) {
    }

    @PostMapping("/{userId}/grants")
    public PointResponse grant(@PathVariable String userId, @Valid @RequestBody GrantRequest req) {
        validateUserId(userId);
        return new PointResponse(userId, points.add(userId, req.amount()));
    }

    @GetMapping("/{userId}")
    public PointResponse get(@PathVariable String userId) {
        validateUserId(userId);
        // an expired order gives its points back; make that visible to this read (R6.2 applies to points too)
        expiry.expireDue();
        return new PointResponse(userId, points.find(userId));
    }

    private static void validateUserId(String userId) {
        if (userId.isBlank() || userId.length() > 50) {
            throw ApiException.validation("userId must be 1-50 non-blank characters");
        }
    }
}
