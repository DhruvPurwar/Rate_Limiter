package com.ratelimit;

import com.ratelimit.exception.RateLimitExceededException;
import com.ratelimit.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RateLimiterConcurrencyTest {

    @Autowired
    private RateLimiterService rateLimiterService;

    /**
     * Fires 20 concurrent requests for the same user.
     * Exactly 5 should succeed; the rest should be throttled.
     *
     * This test validates correctness under parallel calls — the key requirement.
     */
    @Test
    void concurrentRequests_onlyFiveAllowedPerMinute() throws InterruptedException {
        String userId = "concurrent-test-user-" + System.currentTimeMillis();
        int totalRequests = 20;

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger throttled = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await(); // all threads fire simultaneously
                    rateLimiterService.checkAndRecord(userId);
                    allowed.incrementAndGet();
                } catch (RateLimitExceededException e) {
                    throttled.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads at once
        doneLatch.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(5, allowed.get(), "Exactly 5 requests should be allowed");
        assertEquals(15, throttled.get(), "Remaining 15 should be throttled");
    }

    /**
     * Different users should have independent rate limit windows.
     */
    @Test
    void differentUsers_independentWindows() {
        String user1 = "user-A-" + System.currentTimeMillis();
        String user2 = "user-B-" + System.currentTimeMillis();

        // Exhaust user1's limit
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> rateLimiterService.checkAndRecord(user1));
        }
        assertThrows(RateLimitExceededException.class, () -> rateLimiterService.checkAndRecord(user1));

        // user2 should be unaffected
        assertDoesNotThrow(() -> rateLimiterService.checkAndRecord(user2));
    }
}
