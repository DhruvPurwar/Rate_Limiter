# Rate-Limited API Service

A production-considerate REST API implementing per-user rate limiting using a **Sliding Window Counter** algorithm in Java + Spring Boot.

---

## API Endpoints

### `POST /request`
Accepts a user request subject to rate limiting.

**Request body:**
```json
{
  "userId": "alice",
  "payload": { "action": "search", "query": "java concurrency" }
}
```

**Responses:**

| Status | Meaning |
|--------|---------|
| `200 OK` | Request accepted |
| `400 Bad Request` | Missing `userId` or `payload` |
| `429 Too Many Requests` | Rate limit exceeded (max 5/min per user) |

**429 response body:**
```json
{
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit exceeded for user 'alice'. Max 5 requests/minute.",
  "timestamp": "2026-04-21T10:30:00Z"
}
```

Response headers on 429:
- `X-RateLimit-Limit: 5`
- `Retry-After: 60`

---

### `GET /stats`
Returns per-user request statistics.

**Optional query param:** `?userId=alice` (filters to one user)

**Response:**
```json
{
  "totalUsers": 2,
  "users": [
    {
      "userId": "alice",
      "totalRequests": 7,
      "throttledRequests": 2,
      "activeRequestsInWindow": 5,
      "lastRequestTimestampMs": 1713694200000
    }
  ]
}
```

---

## Running the Project

### Option 1: Maven (requires JDK 17+)
```bash
./mvnw spring-boot:run
```

### Option 2: JAR
```bash
./mvnw package -DskipTests
java -jar target/rate-limited-api-1.0.0.jar
```

### Option 3: Docker Compose
```bash
docker-compose up --build
```

### Running Tests
```bash
./mvnw test
```

The test suite includes a **concurrent stress test** — 20 threads fire simultaneously for the same user; exactly 5 should be allowed.

---

## Quick Smoke Test (curl)

```bash
# Send 6 requests — 6th should 429
for i in {1..6}; do
  echo "--- Request $i ---"
  curl -s -X POST http://localhost:8080/request \
    -H "Content-Type: application/json" \
    -d '{"userId":"alice","payload":{"msg":"hello"}}' | jq .
done

# Check stats
curl -s http://localhost:8080/stats | jq .

# Check single user
curl -s "http://localhost:8080/stats?userId=alice" | jq .
```

---

## Design Decisions

### 1. Sliding Window Counter (not Token Bucket)

The spec says "max 5 requests per minute" with no mention of burst allowance. Sliding Window Counter models this exactly:

- A circular array of 60 one-second buckets
- On each request, we evict stale buckets and sum the active 60
- Zero background threads — eviction is lazy

**Token Bucket** would have been better if we wanted to allow short bursts (e.g., 10 requests in 2 seconds as long as the per-minute average holds). That's not what the spec says.

**Sliding Window Log** (storing raw timestamps) would be more precise at second-level boundaries but uses O(limit) memory per user vs O(60) fixed buckets. At scale, O(60) wins.

### 2. Per-user `synchronized` window, not global lock

Each `UserRateLimitWindow` synchronizes on itself. The `RateLimiterService` uses `ConcurrentHashMap.computeIfAbsent()` for thread-safe window creation.

This means:
- Two requests from different users never block each other
- Two concurrent requests from the same user contend only on that user's window lock

A single `synchronized` on the service would have serialized all requests globally — a bottleneck.

### 3. `AtomicLong` for stats, `synchronized` for window logic

Stats reads (`totalRequests`, `throttledRequests`) use `AtomicLong` so `GET /stats` never needs to acquire the window lock. Stats reads are always non-blocking.

The sliding window arithmetic (bucket eviction + sum) is more complex and requires atomicity across multiple fields, so it stays inside `synchronized`.

### 4. HTTP 429 + `Retry-After` header

RFC 6585 defines 429 as the standard status for rate limiting. Including `Retry-After: 60` is a production courtesy — well-behaved clients (and retry libraries) use it to back off intelligently.

### 5. Externalized config

`ratelimit.max-requests-per-minute` is in `application.properties` and can be overridden via environment variable (`RATELIMIT_MAX_REQUESTS_PER_MINUTE=10`). No code change needed to tune the limit.

---

## What I Would Improve With More Time

### Distributed rate limiting (Redis)
The in-memory store is single-instance only. With horizontal scaling, each pod has its own counter — user A could make 5 requests to pod-1 and 5 to pod-2, bypassing the limit entirely.

**Fix:** Use Redis with Lua scripts for atomic `INCR + EXPIRE`, or the `redis.call('TIME')` + sorted set pattern for true sliding windows. Spring Data Redis makes this straightforward.

### Persistent stats storage
Current stats are lost on restart. A time-series DB (InfluxDB, Prometheus + Grafana) or even Postgres would give durable, queryable history.

### User authentication / API keys
Currently, `userId` is caller-supplied and trusted. In production, it should be derived from an authenticated JWT or API key, not a free-form field.

### Rate limit tiers
Different users (free vs paid) might have different limits. A `RateLimitTierService` that looks up per-user config would be a natural next layer.

### Observability
Add Micrometer metrics: `rate_limit.allowed.count`, `rate_limit.throttled.count`, `rate_limit.active_window` — tagged by `userId`. Wire to Prometheus/Grafana.

### Retry queueing (bonus item)
For non-latency-sensitive payloads, rejected requests could be put on a `DelayQueue` and retried after the window resets, rather than returning a 429.

---

## Architecture Overview

```
POST /request
     │
     ▼
ApiController
     │  validate()
     ▼
RateLimiterService
     │  computeIfAbsent → UserRateLimitWindow
     │  tryAcquire()
     ├─ allowed → 200 OK
     └─ denied  → RateLimitExceededException → GlobalExceptionHandler → 429
```

```
UserRateLimitWindow (per user)
  ┌────────────────────────────────────────┐
  │  long[] buckets[60]  (circular array)  │
  │  index = (nowSeconds % 60)             │
  │  evict stale on each call              │
  │  sum active → compare to limit         │
  └────────────────────────────────────────┘
```

---

## Tech Stack

- Java 17
- Spring Boot 3.2
- Maven
- Docker / Docker Compose
- JUnit 5 (concurrency integration tests)
