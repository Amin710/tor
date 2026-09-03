package com.v2ray.ang.ui.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnectButtonWaitingIndicatorTest {
    private val source =
        File("src/main/java/com/v2ray/ang/ui/main/MainScreen.kt").readText()

    @Test
    fun busyStateOwnsAContinuousRotatingGradientRing() {
        assertTrue(source.contains("if (busy) {\n            SorenConnectingRing()"))
        assertTrue(source.contains("rememberInfiniteTransition(label = \"connect-waiting-ring\")"))
        assertTrue(source.contains("infiniteRepeatable("))
        assertTrue(source.contains("easing = LinearEasing"))
        assertTrue(source.contains(".rotate(rotationDegrees)"))
        assertTrue(source.contains("Brush.sweepGradient("))
        assertTrue(source.contains("cap = StrokeCap.Round"))
    }

    @Test
    fun waitingIndicatorDoesNotChangeConnectionInteractionContract() {
        assertTrue(
            source.contains(
                ".clickable(enabled = !busy, role = Role.Button, onClick = onClick)"
            )
        )
        assertTrue(source.contains("uiState.status is MainStatus.SelectingServer ||"))
        assertTrue(source.contains("uiState.status is MainStatus.Connecting,"))
        assertTrue(source.contains("stateDescription = accessibleState"))
    }
}
