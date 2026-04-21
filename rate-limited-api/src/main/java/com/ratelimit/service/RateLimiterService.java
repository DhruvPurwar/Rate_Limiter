package com.ratelimit.service;

import com.ratelimit.exception.RateLimitExceededException;
import com.ratelimit.model.UserRateLimitWindow;
import com.ratelimit.model.UserStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the per-user rate limit windows and enforces the limit on each request.
 *
 * Thread-safety:
 * - userWindows is a ConcurrentHashMap — safe for concurrent get/put.
 * - computeIfAbsent is atomic — no two threads will create a window for the same user.
 * - The rate limit check itself is delegated to UserRateLimitWindow which is individually synchronized.
 *
 * Why ConcurrentHashMap + computeIfAbsent instead of synchronized HashMap?
 * - Striped locking inside CHM allows concurrent reads/writes across different keys.
 * - A global synchronized block would serialize all user lookups — unnecessary contention.
 */
@Service
public class RateLimiterService {

    // SHARED STATE — ConcurrentHashMap, individual windows self-synchronized
    private final ConcurrentHashMap<String, UserRateLimitWindow> userWindows = new ConcurrentHashMap<>();

    @Value("${ratelimit.max-requests-per-minute:5}")
    private int maxRequestsPerMinute;

    /**
     * Checks and records a request for the given user.
     * Throws RateLimitExceededException if the user has hit their limit.
     */
    public void checkAndRecord(String userId) {
        UserRateLimitWindow window = userWindows.computeIfAbsent(
            userId,
            id -> new UserRateLimitWindow(id, maxRequestsPerMinute)
        );

        if (!window.tryAcquire()) {
            throw new RateLimitExceededException(userId, maxRequestsPerMinute);
        }
    }

    public List<UserStats> getAllStats() {
        return userWindows.values().stream()
            .map(w -> new UserStats(
                w.getUserId(),
                w.getTotalRequests(),
                w.getThrottledRequests(),
                w.getActiveRequestsInCurrentWindow(),
                w.getLastRequestTimestamp()
            ))
            .toList();
    }

    public UserStats getStatsForUser(String userId) {
        UserRateLimitWindow w = userWindows.get(userId);
        if (w == null) return null;
        return new UserStats(
            w.getUserId(),
            w.getTotalRequests(),
            w.getThrottledRequests(),
            w.getActiveRequestsInCurrentWindow(),
            w.getLastRequestTimestamp()
        );
    }
}
