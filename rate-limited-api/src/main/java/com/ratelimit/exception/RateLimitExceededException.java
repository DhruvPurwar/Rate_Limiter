package com.ratelimit.exception;

public class RateLimitExceededException extends RuntimeException {

    private final String userId;
    private final int limitPerMinute;

    public RateLimitExceededException(String userId, int limitPerMinute) {
        super(String.format("Rate limit exceeded for user '%s'. Max %d requests/minute.", userId, limitPerMinute));
        this.userId = userId;
        this.limitPerMinute = limitPerMinute;
    }

    public String getUserId() { return userId; }
    public int getLimitPerMinute() { return limitPerMinute; }
}
