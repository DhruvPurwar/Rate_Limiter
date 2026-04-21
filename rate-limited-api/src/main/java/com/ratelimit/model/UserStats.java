package com.ratelimit.model;

/**
 * Per-user statistics returned by GET /stats
 */
public record UserStats(
    String userId,
    long totalRequests,
    long throttledRequests,
    long activeRequestsInWindow,
    long lastRequestTimestampMs
) {}
