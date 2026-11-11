package com.lshai.cloudscreen

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ImageTask(@Volatile var priority: Int, val order: Long, val work: () -> Unit) : Runnable, Comparable<ImageTask> {
    override fun compareTo(other: ImageTask): Int = compareValuesBy(this, other, { it.priority }, { it.order })
    override fun run() = work()
}

/** Separate capacity ensures waiting speculative requests cannot block visible images. */
internal class ImageRequestScheduler(foregroundThreads: Int = 5, prefetchThreads: Int = 1) {
    private val foreground = ThreadPoolExecutor(foregroundThreads, foregroundThreads, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>())
    private val prefetch = ThreadPoolExecutor(prefetchThreads, prefetchThreads, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>())
    fun execute(task: ImageTask, visible: Boolean) {
        if (visible) { task.priority = 0; foreground.execute(task) } else prefetch.execute(task)
    }
    fun promote(task: ImageTask) {
        if (prefetch.remove(task)) {
            task.priority = 0
            try { foreground.execute(task) } catch (e: java.util.concurrent.RejectedExecutionException) {
                prefetch.execute(task)
            }
        }
    }
    fun clearQueued() { foreground.queue.clear(); prefetch.queue.clear() }
    fun shutdown() { foreground.shutdownNow(); prefetch.shutdownNow() }
}
