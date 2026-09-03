package com.v2ray.ang.ui.main

import com.v2ray.ang.haima.OneShotAdCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPresentationGateTest {
    @Test
    fun coreSuccessKeepsVisibleStateConnecting() {
        val pending = MainUiState(status = MainStatus.Connecting).awaitConnectionPresentation()

        assertTrue(pending.isRunning)
        assertTrue(pending.status is MainStatus.Connecting)
        assertNull(pending.connectedAtEpochMillis)
        assertEquals(1L, pending.successfulConnectionEventId)
    }

    @Test
    fun rawRunningEventCannotBypassPendingGate() {
        val pending = MainUiState(status = MainStatus.Connecting)
            .awaitConnectionPresentation()
            .withCoreRunningState(
                running = true,
                clearTestingText = false,
                nowEpochMillis = 1234L
            )

        assertTrue(pending.isRunning)
        assertTrue(pending.status is MainStatus.Connecting)
        assertNull(pending.connectedAtEpochMillis)
        assertNull(pending.normalAdRouteState())
    }

    @Test
    fun matchingCompletionPresentsConnectedAndStartsVisibleTimer() {
        val pending = MainUiState(status = MainStatus.Connecting).awaitConnectionPresentation()
        val completed = pending.completeConnectionPresentation(
            eventId = pending.successfulConnectionEventId,
            nowEpochMillis = 5678L
        )

        assertTrue(completed.isRunning)
        assertTrue(completed.status is MainStatus.Connected)
        assertEquals(5678L, completed.connectedAtEpochMillis)
        assertEquals(true, completed.normalAdRouteState())
    }

    @Test
    fun staleOrStoppedCompletionIsIgnored() {
        val pending = MainUiState(status = MainStatus.Connecting).awaitConnectionPresentation()
        assertSame(
            pending,
            pending.completeConnectionPresentation(
                eventId = pending.successfulConnectionEventId + 1L,
                nowEpochMillis = 1L
            )
        )

        val stopped = pending.withCoreRunningState(
            running = false,
            clearTestingText = true,
            nowEpochMillis = 2L
        )
        assertSame(
            stopped,
            stopped.completeConnectionPresentation(
                eventId = pending.successfulConnectionEventId,
                nowEpochMillis = 3L
            )
        )
        assertFalse(stopped.isRunning)
        assertTrue(stopped.status is MainStatus.Disconnected)
        assertEquals(false, stopped.normalAdRouteState())
    }

    @Test
    fun adContinuationCompletesExactlyOnce() {
        var calls = 0
        val completion = OneShotAdCompletion { calls += 1 }

        assertTrue(completion.complete())
        assertFalse(completion.complete())
        assertTrue(completion.isCompleted())
        assertEquals(1, calls)
    }
}
