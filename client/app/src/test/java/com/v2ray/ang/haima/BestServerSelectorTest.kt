package com.v2ray.ang.haima

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestServerSelectorTest {
    @Test
    fun choosesLowestLatencyFromFirstHealthyBatch() = runBlocking {
        val touched = mutableListOf<String>()
        val result = BestServerSelector().select((1..10).map(Int::toString)) { guid ->
            touched += guid
            mapOf("1" to 100L, "2" to 35L, "3" to -1L, "4" to 80L, "5" to 50L)[guid]
                ?: error("Second batch must not be probed")
        }

        assertEquals("2", result?.guid)
        assertEquals(35L, result?.latencyMillis)
        assertEquals((1..5).map(Int::toString).toSet(), touched.toSet())
    }

    @Test
    fun fallsBackToSecondBatchOnlyWhenFirstBatchFails() = runBlocking {
        val result = BestServerSelector().select((1..10).map(Int::toString)) { guid ->
            if (guid.toInt() <= 5) -1L else if (guid == "8") 42L else 90L
        }

        assertEquals("8", result?.guid)
        assertEquals(1, result?.batchIndex)
    }

    @Test
    fun returnsNullWhenEveryServerFails() = runBlocking {
        assertNull(BestServerSelector().select(listOf("a", "b")) { -1L })
    }
}
