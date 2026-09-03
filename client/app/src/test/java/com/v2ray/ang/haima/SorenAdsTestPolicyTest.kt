package com.v2ray.ang.haima

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SorenAdsTestPolicyTest {
    @Test
    fun fullScreenWatchdogIsIndependentFromPlacementLoadTimeout() {
        assertEquals(90_000L, SorenAds.FULL_SCREEN_CALLBACK_TIMEOUT_MS)
    }

    @Test
    fun compiledAdsUseOnlyServerProvidedUnitIds() {
        val remote = SorenAdsSettings(
            enabled = true,
            bannerUnitId = "server-banner",
            interstitialUnitId = "server-interstitial",
            rewardedUnitId = "server-rewarded",
            testMode = true,
            placements = SorenAdPlacements(
                splash = SorenAdPlacement(
                    enabled = true,
                    format = "app_open",
                    unitId = "server-splash"
                )
            )
        )

        val result = remote.forCurrentBuild(admobCompiledIn = true)

        assertTrue(result.enabled)
        assertEquals("server-banner", result.bannerUnitId)
        assertEquals("server-interstitial", result.interstitialUnitId)
        assertEquals("server-rewarded", result.rewardedUnitId)
        assertEquals("server-splash", result.placements.splash.unitId)
        assertEquals("interstitial", result.placements.splash.format)
    }

    @Test
    fun regularBuildReturnsRemotePolicyUnchanged() {
        val remote = SorenAdsSettings(
            enabled = true,
            bannerUnitId = "production-banner",
            interstitialUnitId = "production-interstitial",
            rewardedUnitId = "production-rewarded",
            interstitialEveryConnections = 4,
            umpRequired = true,
            testMode = false,
            placements = SorenAdPlacements(
                beforeConnect = SorenAdPlacement(
                    enabled = true,
                    unitId = "production-before",
                    everyNActions = 4,
                    cooldownSeconds = 90,
                    maxPerDay = 3
                )
            )
        )

        val result = remote.forCurrentBuild(admobCompiledIn = true)

        assertSame(remote, result)
        assertEquals(remote, result)
    }

    @Test
    fun backendTestModeNormalizesSplashToZaalInterstitialWithoutAForcedBuild() {
        val remote = SorenAdsSettings(
            enabled = true,
            testMode = true,
            placements = SorenAdPlacements(
                splash = SorenAdPlacement(
                    enabled = true,
                    format = "app_open",
                    unitId = ""
                )
            )
        )

        val result = remote.forCurrentBuild(admobCompiledIn = true)

        assertEquals("interstitial", result.placements.splash.format)
        assertEquals("", result.placements.splash.unitId)
        assertTrue(result.testMode)
    }

    @Test
    fun adFreePublishingBuildIgnoresEveryRemoteAdSetting() {
        val remote = SorenAdsSettings(
            enabled = true,
            bannerUnitId = "remote-banner",
            interstitialUnitId = "remote-interstitial",
            rewardedUnitId = "remote-rewarded",
            umpRequired = true,
            testMode = true,
            placements = SorenAdPlacements(
                beforeConnect = SorenAdPlacement(enabled = true, unitId = "before"),
                afterConnect = SorenAdPlacement(enabled = true, unitId = "after"),
                splash = SorenAdPlacement(enabled = true, unitId = "splash"),
                appOpen = SorenAdPlacement(enabled = true, format = "app_open", unitId = "open")
            )
        )

        val result = remote.forCurrentBuild(admobCompiledIn = false)

        assertFalse(result.enabled)
        assertFalse(result.testMode)
        assertFalse(result.umpRequired)
        assertEquals("", result.bannerUnitId)
        assertEquals("", result.interstitialUnitId)
        assertEquals("", result.rewardedUnitId)
        assertFalse(result.placements.beforeConnect.enabled)
        assertFalse(result.placements.afterConnect.enabled)
        assertFalse(result.placements.splash.enabled)
        assertFalse(result.placements.appOpen.enabled)
    }

}
