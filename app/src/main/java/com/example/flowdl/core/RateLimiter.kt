package com.example.flowdl.core

/**
 * Token-bucket limiter shared by every worker of one task.
 * A limit of 0 means "unlimited" and costs nothing.
 */
class RateLimiter {
    private var limitBps: Long = 0L
    private var allowance: Double = 0.0
    private var lastRefillNs: Long = System.nanoTime()

    @Synchronized
    fun setLimit(bytesPerSecond: Long) {
        limitBps = bytesPerSecond.coerceAtLeast(0L)
        allowance = 0.0
        lastRefillNs = System.nanoTime()
    }

    fun throttle(bytes: Int) {
        if (limitBps <= 0L || bytes <= 0) return
        while (true) {
            var waitMs = 0L
            synchronized(this) {
                val now = System.nanoTime()
                val elapsed = (now - lastRefillNs) / 1_000_000_000.0
                allowance = (allowance + elapsed * limitBps).coerceAtMost(limitBps.toDouble())
                lastRefillNs = now
                allowance -= bytes
                if (allowance < 0.0) {
                    waitMs = ((-allowance) * 1000.0 / limitBps).toLong().coerceIn(1L, 120L)
                }
            }
            if (waitMs <= 0L) return
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
