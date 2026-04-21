package com.ratelimit.controller;

import com.ratelimit.model.ApiRequest;
import com.ratelimit.model.UserStats;
import com.ratelimit.service.RateLimiterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class ApiController {

    private final RateLimiterService rateLimiterService;

    public ApiController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * POST /request
     *
     * Accepts a user request, enforces rate limiting, and echoes back success.
     * In a real system this would dispatch to a downstream service or queue.
     *
     * Returns:
     *   200 OK             — request accepted
     *   400 Bad Request    — missing/invalid user_id or payload
     *   429 Too Many Requests — rate limit exceeded
     */
    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> handleRequest(@RequestBody ApiRequest request) {
        request.validate();

        // This throws RateLimitExceededException if limit is hit — handled by GlobalExceptionHandler
        rateLimiterService.checkAndRecord(request.userId());

        return ResponseEntity.ok(Map.of(
            "status", "ACCEPTED",
            "userId", request.userId(),
            "timestamp", Instant.now().toString()
        ));
    }

    /**
     * GET /stats
     *
     * Returns per-user request statistics.
     * Optional query param: ?userId=alice to filter to one user.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
        @RequestParam(required = false) String userId
    ) {
        if (userId != null) {
            UserStats stats = rateLimiterService.getStatsForUser(userId);
            if (stats == null) {
                return ResponseEntity.ok(Map.of("message", "No data for user: " + userId));
            }
            return ResponseEntity.ok(Map.of("user", stats));
        }

        List<UserStats> allStats = rateLimiterService.getAllStats();
        return ResponseEntity.ok(Map.of(
            "totalUsers", allStats.size(),
            "users", allStats
        ));
    }

    /** Simple health probe — useful before integrating Actuator */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
