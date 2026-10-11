package io.github.robertmtalbot.takbridge.takserver

import java.time.Duration
import java.time.Instant

/** Allows at most [max] events in any sliding [window]. Not thread-safe; guard it externally. */
class RateLimiter(private val max: Int, private val window: Duration) {
    private val times = ArrayDeque<Instant>()

    /** True when an event at [now] is within the limit; the event is then counted. */
    fun tryAcquire(now: Instant): Boolean {
        val cutoff = now.minus(window)
        while (times.isNotEmpty() && !times.first().isAfter(cutoff)) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(now)
        return true
    }
}
