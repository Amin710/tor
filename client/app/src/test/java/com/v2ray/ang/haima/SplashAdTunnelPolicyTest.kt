package com.v2ray.ang.haima

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplashAdTunnelPolicyTest {
    private val placement = SorenAdPlacement(
        enabled = true,
        format = "interstitial",
        unitId = "server-provided-unit-id",
        timeoutMs = 12_000
    )
    private val servers = listOf(
        SorenServer(id = "ad-1", config = "vless://ad-server", enabled = true)
    )

    @Test
    fun firstLaunchWithoutVpnConsentSkipsWithoutStartingAnything() {
        val result = decide(permissionGranted = false)
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.VPN_PERMISSION_NOT_GRANTED),
            result
        )
    }

    @Test
    fun existingUserVpnAlwaysWinsAndIsNeverReplaced() {
        val result = decide(permissionGranted = true, vpnRunning = true)
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.VPN_ALREADY_RUNNING),
            result
        )
    }

    @Test
    fun eligibleReturningUserStartsOnlyWithSeparateAdServerAndFullScreenInterstitial() {
        assertEquals(
            SplashAdLaunchDecision.StartTemporaryTunnel,
            decide(permissionGranted = true)
        )
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.NO_AD_SERVERS),
            decide(permissionGranted = true, adServers = emptyList())
        )
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.INVALID_PLACEMENT),
            decide(permissionGranted = true, adPlacement = placement.copy(format = "app_open"))
        )
    }

    @Test
    fun blankRemoteUnitIsRejectedBecauseClientHasNoDemoFallback() {
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.INVALID_PLACEMENT),
            decide(
                permissionGranted = true,
                adPlacement = placement.copy(unitId = ""),
                testMode = true
            )
        )
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.INVALID_PLACEMENT),
            decide(
                permissionGranted = true,
                adPlacement = placement.copy(format = "app_open", unitId = ""),
                testMode = false
            )
        )
    }

    @Test
    fun productionUsesFullScreenAppOpenAndRejectsLaunchInterstitial() {
        val production = placement.copy(format = "app_open", unitId = "production-app-open")
        assertEquals(
            SplashAdLaunchDecision.StartTemporaryTunnel,
            decide(permissionGranted = true, adPlacement = production, testMode = false)
        )
        assertEquals(
            SplashAdLaunchDecision.Skip(SplashAdSkipReason.INVALID_PLACEMENT),
            decide(permissionGranted = true, adPlacement = placement, testMode = false)
        )
    }

    @Test
    fun onlyTerminalCoordinatorStateReleasesMainEntryGate() {
        assertFalse(SplashAdTunnelState.AwaitingBootstrap.isEntryGateComplete)
        assertFalse(SplashAdTunnelState.LoadingAd.isEntryGateComplete)
        assertFalse(SplashAdTunnelState.StoppingTunnel.isEntryGateComplete)
        assertTrue(
            SplashAdTunnelState.Complete(SplashAdTunnelOutcome.AD_FINISHED)
                .isEntryGateComplete
        )
    }

    @Test
    fun testSplashUsesZaalInterstitialAndProductionFallbackIsAlsoImmersive() {
        val adsSource = File("src/admob/java/com/v2ray/ang/haima/SorenAds.kt").readText()
        val mainSource = File("src/main/java/com/v2ray/ang/ui/main/MainScreen.kt").readText()

        assertTrue(adsSource.contains("requestGatedSplashInterstitial"))
        assertTrue(adsSource.contains("InterstitialAd.load("))
        assertTrue(adsSource.contains("requestGatedSplashAppOpen"))
        assertTrue(adsSource.contains("AppOpenAd.load("))
        assertTrue(adsSource.contains("ad.setImmersiveMode(true)"))
        assertTrue(mainSource.contains("splashAdTunnelState.isEntryGateComplete"))
    }

    private fun decide(
        permissionGranted: Boolean,
        vpnRunning: Boolean = false,
        adPlacement: SorenAdPlacement = placement,
        adServers: List<SorenServer> = servers,
        testMode: Boolean = true
    ): SplashAdLaunchDecision = SplashAdTunnelPolicy.decide(
        vpnAlreadyRunning = vpnRunning,
        vpnPermissionPreviouslyGranted = permissionGranted,
        vpnModeAvailable = true,
        adsEnabled = true,
        testMode = testMode,
        placement = adPlacement,
        adServers = adServers
    )
}
