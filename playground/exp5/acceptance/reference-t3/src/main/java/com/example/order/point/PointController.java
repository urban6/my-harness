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

    /** The "//grants" and "/" mappings make an empty userId a 400 (P1.2) instead of an unmatched route. */
    @PostMapping({"/{userId}/grants", "//grants"})
    public PointResponse grant(@PathVariable(required = false) String userId, @Valid @RequestBody GrantRequest req) {
        validateUserId(userId);
        return new PointResponse(userId, points.add(userId, req.amount()));
    }

    @GetMapping({"/{userId}", "/"})
    public PointResponse get(@PathVariable(required = false) String userId) {
        validateUserId(userId);
        expiry.expireDue();
        return new PointResponse(userId, points.balance(userId));
    }

    private static void validateUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 50) {
            throw ApiException.validation("userId must be 1-50 non-blank characters");
        }
    }
}
