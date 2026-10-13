package org.coloradomesh.takbridge.takserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RateLimiterTest {
    private val start = Instant.parse("2026-10-11T12:00:00Z")

    @Test
    fun allowsUpToTheLimitThenRefuses() {
        val limiter = RateLimiter(3, Duration.ofMinutes(1))
        repeat(3) { assertTrue(limiter.tryAcquire(start.plusSeconds(it.toLong()))) }
        assertFalse(limiter.tryAcquire(start.plusSeconds(10)))
    }

    @Test
    fun recoversAsOldEventsLeaveTheWindow() {
        val limiter = RateLimiter(2, Duration.ofMinutes(1))
        assertTrue(limiter.tryAcquire(start))
        assertTrue(limiter.tryAcquire(start.plusSeconds(30)))
        assertFalse(limiter.tryAcquire(start.plusSeconds(59)))
        assertTrue(limiter.tryAcquire(start.plusSeconds(60)))
        assertFalse(limiter.tryAcquire(start.plusSeconds(61)))
        assertTrue(limiter.tryAcquire(start.plusSeconds(90)))
    }

    @Test
    fun aRefusedEventIsNotCounted() {
        val limiter = RateLimiter(1, Duration.ofSeconds(10))
        assertTrue(limiter.tryAcquire(start))
        repeat(5) { assertFalse(limiter.tryAcquire(start.plusSeconds(5))) }
        assertTrue(limiter.tryAcquire(start.plusSeconds(10)))
    }
}
