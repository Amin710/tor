package com.v2ray.ang.ui.main

import com.v2ray.ang.haima.BootstrapStage
import com.v2ray.ang.haima.BootstrapStatus
import com.v2ray.ang.haima.SplashAdTunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootstrapGateTest {
    @Test
    fun errorNeverEntersMainScreen() {
        assertFalse(
            canEnterMainScreen(
                BootstrapStatus.Error("network failed"),
                minimumSplashElapsed = true
            )
        )
    }

    @Test
    fun loadingNeverEntersMainScreen() {
        assertFalse(canEnterMainScreen(BootstrapStatus.Loading(), minimumSplashElapsed = true))
    }

    @Test
    fun readyWaitsForMinimumSplashAndThenEnters() {
        assertFalse(canEnterMainScreen(BootstrapStatus.Ready, minimumSplashElapsed = false))
        assertTrue(canEnterMainScreen(BootstrapStatus.Ready, minimumSplashElapsed = true))
    }

    @Test
    fun readyStillWaitsForTemporarySplashAdCleanup() {
        assertFalse(
            canEnterMainScreen(
                BootstrapStatus.Ready,
                minimumSplashElapsed = true,
                splashAdGateComplete = false
            )
        )
        assertTrue(
            canEnterMainScreen(
                BootstrapStatus.Ready,
                minimumSplashElapsed = true,
                splashAdGateComplete = true
            )
        )
    }

    @Test
    fun readyUsesTheRealSplashAdStageButErrorsAreNeverOverridden() {
        assertEquals(
            BootstrapStatus.Loading(BootstrapStage.LOADING_SPLASH_AD),
            splashBootstrapStatus(BootstrapStatus.Ready, SplashAdTunnelState.LoadingAd)
        )
        val error = BootstrapStatus.Error("offline")
        assertEquals(
            error,
            splashBootstrapStatus(error, SplashAdTunnelState.LoadingAd)
        )
    }

    @Test
    fun notificationPermissionIsDeferredUntilSplashAdGateCompletes() {
        val activity = File("src/main/java/com/v2ray/ang/ui/main/MainActivity.kt").readText()
        val completionObserver = activity.indexOf("if (complete) requestNotificationPermissionIfNeeded()")
        val permissionLaunch = activity.indexOf(
            "requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)",
            activity.indexOf("private fun requestNotificationPermissionIfNeeded")
        )

        assertTrue(completionObserver >= 0)
        assertTrue(permissionLaunch > completionObserver)
    }
}
