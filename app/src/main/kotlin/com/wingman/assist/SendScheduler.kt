package com.wingman.assist

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fires scheduled replies at their computed instant.
 *
 * Skeleton uses an in-process ScheduledExecutorService — simple and precise, but it
 * does NOT survive the process being killed. For production, swap this for WorkManager
 * OneTimeWorkRequest(setInitialDelay(...)) so sends survive restarts, and have the
 * Worker signal the accessibility service to perform the send.
 */
class SendScheduler(private val onFire: (ScheduledReply) -> Unit) {

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun schedule(reply: ScheduledReply) {
        cancel(reply.conversationId)
        val delayMs = (reply.sendAt.toEpochMilli() - Instant.now().toEpochMilli()).coerceAtLeast(0)
        val future = exec.schedule({
            pending.remove(reply.conversationId)
            onFire(reply)
        }, delayMs, TimeUnit.MILLISECONDS)
        pending[reply.conversationId] = future
    }

    fun cancel(conversationId: String) {
        pending.remove(conversationId)?.cancel(false)
    }

    fun cancelAll() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }
}
