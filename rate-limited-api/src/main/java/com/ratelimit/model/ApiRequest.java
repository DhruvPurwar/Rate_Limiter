package com.ratelimit.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiRequest(
        @JsonProperty("userId") String userId,
        @JsonProperty("payload") Object payload
) {
    public void validate() {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }
}