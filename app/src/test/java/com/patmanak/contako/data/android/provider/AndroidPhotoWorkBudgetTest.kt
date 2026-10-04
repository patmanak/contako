package com.patmanak.contako.data.android.provider

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidPhotoWorkBudgetTest {
    @Test fun independentProjectionWorkersShareOneDecodedPhotoScope() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val workers = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..32).map {
                workers.submit<Int> {
                    AndroidPhotoWorkBudget.withDecodedPhoto {
                        val count = active.incrementAndGet()
                        maximum.updateAndGet { previous -> maxOf(previous, count) }
                        try { Thread.yield(); 1 } finally { active.decrementAndGet() }
                    }
                }
            }
            assertEquals(32, tasks.sumOf { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, maximum.get())
            assertEquals(0, active.get())
        } finally { workers.shutdownNow() }
    }

    @Test fun rejectedPhotoReleasesBudgetForTheNextWorker() {
        assertThrows(IllegalStateException::class.java) {
            AndroidPhotoWorkBudget.withDecodedPhoto { error("synthetic rejection") }
        }
        val workers = Executors.newSingleThreadExecutor()
        try {
            assertEquals(7, workers.submit<Int> {
                AndroidPhotoWorkBudget.withDecodedPhoto { 7 }
            }.get(5, TimeUnit.SECONDS).toInt())
        } finally { workers.shutdownNow() }
    }
}
