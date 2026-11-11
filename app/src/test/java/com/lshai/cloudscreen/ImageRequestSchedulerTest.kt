package com.lshai.cloudscreen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ImageRequestSchedulerTest {
    @Test fun visibleRequestsDoNotWaitForBusyPrefetchAndQueuedPrefetchCanBePromoted() {
        val scheduler = ImageRequestScheduler(1, 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val visible = CountDownLatch(1)
        val promoted = CountDownLatch(1)
        try {
            scheduler.execute(ImageTask(1, 1) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }, false)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val waiting = ImageTask(1, 2) { promoted.countDown() }
            scheduler.execute(waiting, false)
            scheduler.execute(ImageTask(0, 3) { visible.countDown() }, true)
            assertTrue("visible image should not wait for prefetch", visible.await(3, TimeUnit.SECONDS))
            scheduler.promote(waiting)
            assertTrue("visible request for queued prefetch should be promoted", promoted.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            scheduler.shutdown()
        }
    }
}
