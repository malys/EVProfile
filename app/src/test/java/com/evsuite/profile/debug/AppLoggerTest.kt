package com.evsuite.profile.debug

import com.evsuite.hardware.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-906] The log buffer is written from the profile application thread and read by
 * him. These tests cover concurrency cap and incremental rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLoggerTest {

    private val maxEntries = 400

    @Before
    fun setUp() {
        AppLogger.clear()
    }

    @Test
    fun `buffer never exceeds its capacity`() {
        repeat(maxEntries + 250) { AppLogger.i("T", "msg$it") }
        assertEquals(maxEntries, AppLogger.entries.size)
    }

    @Test
    fun `oldest entries are evicted first`() {
        repeat(maxEntries + 5) { AppLogger.i("T", "msg$it") }
        val entries = AppLogger.entries
        assertEquals("msg5", entries.first().msg)
        assertEquals("msg${maxEntries + 4}", entries.last().msg)
    }

    @Test
    fun `totalCount includes evicted entries`() {
        repeat(maxEntries + 50) { AppLogger.i("T", "msg$it") }
        assertEquals((maxEntries + 50).toLong(), AppLogger.totalCount)
        assertEquals(maxEntries, AppLogger.entries.size)
    }

    @Test
    fun `concurrent writes do not exceed capacity`() {
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.await()
                try { repeat(perThread) { AppLogger.i("T$t", "m$it") } } finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        assertEquals(maxEntries, AppLogger.entries.size)
        assertEquals((threads * perThread).toLong(), AppLogger.totalCount)
    }

    // ---- Incremental rendering ----

    @Test
    fun `entriesSince returns only new entries`() {
        repeat(10) { AppLogger.i("T", "msg$it") }
        val seen = AppLogger.totalCount
        AppLogger.i("T", "nouveau1")
        AppLogger.i("T", "nouveau2")

        val since = AppLogger.entriesSince(seen)
        assertNotNull(since)
        assertEquals(2, since!!.size)
        assertEquals("nouveau1", since[0].msg)
        assertEquals("nouveau2", since[1].msg)
    }

    @Test
    fun `entriesSince returns an empty list when nothing changed`() {
        repeat(3) { AppLogger.i("T", "msg$it") }
        assertEquals(emptyList<AppLogger.Entry>(), AppLogger.entriesSince(AppLogger.totalCount))
    }

    @Test
    fun `entriesSince requests a full render after entries were evicted`() {
        AppLogger.i("T", "premier")
        val seen = AppLogger.totalCount
        repeat(maxEntries + 10) { AppLogger.i("T", "msg$it") }
        // What the caller has not yet seen has been evicted: impossible to complete.
        assertNull(AppLogger.entriesSince(seen))
    }

    @Test
    fun `entriesSince requests a full render after clear`() {
        repeat(10) { AppLogger.i("T", "msg$it") }
        val seen = AppLogger.totalCount
        AppLogger.clear()
        assertNull(AppLogger.entriesSince(seen))
    }
}
