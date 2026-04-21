package com.ratelimit.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks rate limit state and request statistics for a single user.
 *
 * Algorithm: Sliding Window Log
 * - We store a count of requests in a fixed-size circular bucket array.
 * - Each bucket represents a 1-second slice of time.
 * - On every request, we evict stale buckets and sum the active window.
 *
 * Why Sliding Window Counter over Token Bucket?
 * - Simpler to reason about "X requests per minute" semantics exactly.
 * - No background thread needed — eviction is lazy (on each request).
 * - Token Bucket is better when you want burst allowance beyond the rate;
 *   here, the spec is strict: max 5 per minute, no burst.
 *
 * Concurrency: all mutable fields are guarded by the intrinsic lock (synchronized).
 * A per-user lock is fine here; we never need to lock across users simultaneously.
 */
public class UserRateLimitWindow {

    private static final int BUCKET_COUNT = 60;      // one bucket per second
    private static final long BUCKET_SIZE_MS = 1_000; // 1 second per bucket

    private final String userId;
    private final int maxRequestsPerMinute;

    // SHARED STATE — guarded by this (synchronized methods)
    private final long[] buckets = new long[BUCKET_COUNT]; // request counts per second-slot
    private long lastEvictedSecond = -1;

    // Stats — atomic so GET /stats reads don't need the window lock
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong throttledRequests = new AtomicLong(0);
    private final AtomicLong lastRequestTimestamp = new AtomicLong(0);

    public UserRateLimitWindow(String userId, int maxRequestsPerMinute) {
        this.userId = userId;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    /**
     * Attempts to record a request for this user.
     *
     * @return true if the request is allowed, false if rate limit exceeded
     */
    public synchronized boolean tryAcquire() {
        long nowMs = System.currentTimeMillis();
        long currentSecond = nowMs / BUCKET_SIZE_MS;

        evictStaleBuckets(currentSecond);

        long windowCount = sumActiveBuckets();
        if (windowCount >= maxRequestsPerMinute) {
            throttledRequests.incrementAndGet();
            return false;
        }

        int slot = (int) (currentSecond % BUCKET_COUNT);
        buckets[slot]++;
        lastEvictedSecond = Math.max(lastEvictedSecond, currentSecond);

        totalRequests.incrementAndGet();
        lastRequestTimestamp.set(nowMs);
        return true;
    }

    /**
     * Zero out any bucket that falls outside the current 60-second window.
     * Called lazily on each request — no background thread needed.
     */
    private void evictStaleBuckets(long currentSecond) {
        long windowStartSecond = currentSecond - BUCKET_COUNT + 1;

        if (lastEvictedSecond < windowStartSecond - 1) {
            // Entire window is stale — clear all
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = 0;
            }
        } else {
            // Clear only the slots that just became stale
            for (long s = lastEvictedSecond + 1; s < windowStartSecond; s++) {
                buckets[(int) (s % BUCKET_COUNT)] = 0;
            }
        }

        lastEvictedSecond = currentSecond - 1; // up to, not including current
    }

    private long sumActiveBuckets() {
        long sum = 0;
        for (long count : buckets) {
            sum += count;
        }
        return sum;
    }

    public String getUserId() { return userId; }
    public long getTotalRequests() { return totalRequests.get(); }
    public long getThrottledRequests() { return throttledRequests.get(); }
    public long getLastRequestTimestamp() { return lastRequestTimestamp.get(); }

    public synchronized long getActiveRequestsInCurrentWindow() {
        evictStaleBuckets(System.currentTimeMillis() / BUCKET_SIZE_MS);
        return sumActiveBuckets();
    }
}
