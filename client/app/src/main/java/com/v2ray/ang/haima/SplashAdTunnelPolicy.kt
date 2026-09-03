package com.v2ray.ang.haima

internal sealed interface SplashAdLaunchDecision {
    data object StartTemporaryTunnel : SplashAdLaunchDecision
    data class Skip(val reason: SplashAdSkipReason) : SplashAdLaunchDecision
}

internal enum class SplashAdSkipReason {
    VPN_ALREADY_RUNNING,
    VPN_PERMISSION_NOT_GRANTED,
    VPN_MODE_UNAVAILABLE,
    ADS_DISABLED,
    PLACEMENT_DISABLED,
    INVALID_PLACEMENT,
    NO_AD_SERVERS,
    CONFIGURATION_UNAVAILABLE
}

/** Pure launch policy kept separate from Android APIs so first-run behavior is regression tested. */
internal object SplashAdTunnelPolicy {
    fun decide(
        vpnAlreadyRunning: Boolean,
        vpnPermissionPreviouslyGranted: Boolean,
        vpnModeAvailable: Boolean,
        adsEnabled: Boolean,
        testMode: Boolean,
        placement: SorenAdPlacement,
        adServers: List<SorenServer>
    ): SplashAdLaunchDecision {
        if (vpnAlreadyRunning) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.VPN_ALREADY_RUNNING)
        }
        if (!vpnPermissionPreviouslyGranted) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.VPN_PERMISSION_NOT_GRANTED)
        }
        if (!vpnModeAvailable) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.VPN_MODE_UNAVAILABLE)
        }
        if (!adsEnabled) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.ADS_DISABLED)
        }
        if (!placement.enabled) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.PLACEMENT_DISABLED)
        }
        val expectedFormat = if (testMode) "interstitial" else "app_open"
        if (!placement.format.equals(expectedFormat, ignoreCase = true) ||
            placement.unitId.isBlank()
        ) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.INVALID_PLACEMENT)
        }
        if (adServers.none { it.enabled && it.config.isNotBlank() }) {
            return SplashAdLaunchDecision.Skip(SplashAdSkipReason.NO_AD_SERVERS)
        }
        return SplashAdLaunchDecision.StartTemporaryTunnel
    }
}

internal sealed interface SplashAdTunnelState {
    data object AwaitingBootstrap : SplashAdTunnelState
    data object Ready : SplashAdTunnelState
    data object CheckingExistingVpn : SplashAdTunnelState
    data object ImportingServers : SplashAdTunnelState
    data class Connecting(val attempt: Int, val total: Int) : SplashAdTunnelState
    data object LoadingAd : SplashAdTunnelState
    data object StoppingTunnel : SplashAdTunnelState
    data class Complete(val outcome: SplashAdTunnelOutcome) : SplashAdTunnelState
}

internal enum class SplashAdTunnelOutcome {
    AD_FINISHED,
    SKIPPED,
    NO_REACHABLE_AD_SERVER,
    TIMED_OUT,
    FAILED
}

internal val SplashAdTunnelState.isEntryGateComplete: Boolean
    get() = this is SplashAdTunnelState.Complete
