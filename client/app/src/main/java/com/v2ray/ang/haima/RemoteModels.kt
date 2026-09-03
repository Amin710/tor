package com.v2ray.ang.haima

import com.google.gson.annotations.SerializedName
import com.v2ray.ang.BuildConfig

data class SorenServer(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("config")
    val config: String = "",
    @SerializedName("priority")
    val priority: Int = 0,
    @SerializedName("enabled")
    val enabled: Boolean = true
)

data class SorenAdsSettings(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("bannerUnitId")
    val bannerUnitId: String = "",
    @SerializedName("interstitialUnitId")
    val interstitialUnitId: String = "",
    @SerializedName("rewardedUnitId")
    val rewardedUnitId: String = "",
    @SerializedName("interstitialEveryConnections")
    val interstitialEveryConnections: Int = 3,
    @SerializedName("requestTimeoutMs")
    val requestTimeoutMs: Int = 8_000,
    @SerializedName("loadTimeoutMs")
    val loadTimeoutMs: Int = 12_000,
    @SerializedName("umpRequired")
    val umpRequired: Boolean = true,
    @SerializedName("testMode")
    val testMode: Boolean = false,
    @SerializedName("placements")
    val placements: SorenAdPlacements = SorenAdPlacements()
)

/** Enforces the binary monetization capability after the signed image is imported. */
internal fun SorenAdsSettings.forCurrentBuild(
    admobCompiledIn: Boolean = BuildConfig.ADMOB_COMPILED_IN
): SorenAdsSettings {
    if (!admobCompiledIn) {
        // The no-ads source set remains a hard compile-time gate for deliberately ad-free builds.
        return copy(
            enabled = false,
            bannerUnitId = "",
            interstitialUnitId = "",
            rewardedUnitId = "",
            umpRequired = false,
            testMode = false,
            placements = SorenAdPlacements()
        )
    }
    // App and unit IDs stay server/build controlled. The client never forces demo IDs.
    return if (testMode && !placements.splash.format.equals("interstitial", true)) {
        copy(placements = placements.copy(
            splash = placements.splash.copy(format = "interstitial")
        ))
    } else {
        this
    }
}

data class SorenAdPlacement(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("format")
    val format: String = "interstitial",
    @SerializedName("unitId")
    val unitId: String = "",
    @SerializedName("everyNActions")
    val everyNActions: Int = 1,
    @SerializedName("cooldownSeconds")
    val cooldownSeconds: Int = 60,
    @SerializedName("timeoutMs")
    val timeoutMs: Int = 12_000,
    @SerializedName("maxPerDay")
    val maxPerDay: Int = 0
)

data class SorenAdPlacements(
    @SerializedName("beforeConnect")
    val beforeConnect: SorenAdPlacement = SorenAdPlacement(),
    @SerializedName("afterConnect")
    val afterConnect: SorenAdPlacement = SorenAdPlacement(),
    @SerializedName("splash")
    val splash: SorenAdPlacement = SorenAdPlacement(),
    @SerializedName("appOpen")
    val appOpen: SorenAdPlacement = SorenAdPlacement(format = "app_open")
)

data class SorenAppSettings(
    @SerializedName("privacyPolicyUrl")
    val privacyPolicyUrl: String = "https://sorenvpn.invalid/privacy",
    @SerializedName("shareUrl")
    val shareUrl: String = "https://play.google.com/store/apps/details?id=com.vpn.tornadovpn",
    @SerializedName("supportUrl")
    val supportUrl: String = "",
    @SerializedName("maintenanceMessage")
    val maintenanceMessage: String = "",
    @SerializedName("forceUpdateMinVersionCode")
    val forceUpdateMinVersionCode: Long = 0,
    @SerializedName("failClosedOnIntegrityError")
    val failClosedOnIntegrityError: Boolean = true,
    @SerializedName("termsUrl")
    val termsUrl: String = "",
    @SerializedName("websiteUrl")
    val websiteUrl: String = "",
    @SerializedName("configRevision")
    val configRevision: Long = 0
)

data class SorenUpdatePolicy(
    @SerializedName("enabled")
    val enabled: Boolean = false,
    @SerializedName("force")
    val force: Boolean = false,
    @SerializedName("minVersionCode")
    val minVersionCode: Long = 0,
    @SerializedName("maxVersionCode")
    val maxVersionCode: Long = 0,
    @SerializedName("title")
    val title: String = "",
    @SerializedName("message")
    val message: String = "",
    @SerializedName("directUrl")
    val directUrl: String = "",
    @SerializedName("playStoreUrl")
    val playStoreUrl: String = ""
)

data class SorenBootstrapPayload(
    @SerializedName("schemaVersion")
    val schemaVersion: Int = 1,
    @SerializedName("audiencePackageName")
    val audiencePackageName: String = "",
    @SerializedName("issuedAtEpochSeconds")
    val issuedAtEpochSeconds: Long = 0,
    @SerializedName("expiresAtEpochSeconds")
    val expiresAtEpochSeconds: Long = 0,
    @SerializedName("servers")
    val servers: List<SorenServer> = emptyList(),
    @SerializedName("adServers")
    val adServers: List<SorenServer> = emptyList(),
    @SerializedName("ads")
    val ads: SorenAdsSettings = SorenAdsSettings(),
    @SerializedName("app")
    val app: SorenAppSettings = SorenAppSettings(),
    @SerializedName("updatePolicy")
    val updatePolicy: SorenUpdatePolicy = SorenUpdatePolicy()
)

enum class BootstrapStage {
    PREPARING,
    DOWNLOADING_PRIMARY,
    DECODING_PRIMARY,
    VERIFYING_PRIMARY,
    DOWNLOADING_FALLBACK,
    DECODING_FALLBACK,
    VERIFYING_FALLBACK,
    IMPORTING,
    CHECKING_AD_ROUTE,
    CONNECTING_AD_ROUTE,
    LOADING_SPLASH_AD,
    FINALIZING
}

sealed interface BootstrapStatus {
    data class Loading(val stage: BootstrapStage = BootstrapStage.PREPARING) : BootstrapStatus
    data object Ready : BootstrapStatus
    data object BackendSetupRequired : BootstrapStatus
    data class UpdateRequired(val policy: SorenUpdatePolicy) : BootstrapStatus
    data class Error(val userMessage: String) : BootstrapStatus
}

internal class SorenUpdateRequiredException(
    val policy: SorenUpdatePolicy
) : Exception("A mandatory application update is required")

data class BestServerSelection(
    val guid: String,
    val latencyMillis: Long,
    val batchIndex: Int
)
