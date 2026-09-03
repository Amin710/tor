package com.v2ray.ang.haima

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2ray.ang.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** A small, Android-independent guard used by every ad-controlled continuation. */
internal class OneShotAdCompletion(private val callback: () -> Unit) {
    private val completed = AtomicBoolean(false)

    fun complete(): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        callback()
        return true
    }

    fun isCompleted(): Boolean = completed.get()
}

/**
 * Owns the AdMob lifecycle for the main process. Ads always fail open: an unavailable ad
 * must never block bootstrap, VPN permission, or the VPN connection itself.
 */
object SorenAds {
    private const val TAG = "TornadoAds"
    private const val BEFORE_CONNECT = "before_connect"
    private const val AFTER_CONNECT = "after_connect"
    private const val SPLASH = "splash"
    private const val APP_OPEN = "app_open"
    private const val PREFS = "soren_ads"
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val INTERSTITIAL_MAX_AGE_MS = 55L * 60L * 1_000L
    private const val APP_OPEN_MAX_AGE_MS = 4L * 60L * 60L * 1_000L
    private const val PENDING_SHOW_MAX_AGE_MS = 2L * 60L * 1_000L
    private const val ROUTE_SETUP_ALLOWANCE_MS = 4_000L
    // This is only a last-resort guard for a lost SDK callback. The placement timeout controls
    // loading; once a full-screen ad is visible we wait for dismiss/show-failure independently.
    internal const val FULL_SCREEN_CALLBACK_TIMEOUT_MS = 90_000L
    private const val FULL_SCREEN_GLOBAL_COOLDOWN_MS = 60_000L
    private val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initializationStarted = AtomicBoolean(false)
    private val fullScreenShowing = AtomicBoolean(false)
    private val appOpenShownThisSession = AtomicBoolean(false)
    private val slots = mutableMapOf<String, InterstitialSlot>()
    private val pendingShows = mutableMapOf<String, PendingShow>()
    private val placementGates = mutableMapOf<String, PlacementGate>()

    private var sdkReadySnapshot by mutableStateOf(false)
    private var bannerReloadSnapshot by mutableLongStateOf(0L)
    private var vpnRouteReadySnapshot by mutableStateOf(false)

    internal val sdkReady: Boolean
        get() = sdkReadySnapshot

    internal val bannerReloadGeneration: Long
        get() = bannerReloadSnapshot

    internal val vpnRouteReady: Boolean
        get() = vpnRouteReadySnapshot

    @Volatile
    private var settings: SorenAdsSettings = SorenAdsSettings()
    private var configGeneration = 0L
    private var consentRequestedGeneration = -1L
    private var vpnConnected = false
    private var vpnRouteGeneration = 0L
    private var temporarySplashRouteActive = false
    private var temporarySplashRouteGeneration = 0L
    private var lastFullScreenClosedAt = 0L
    private var resumedActivity = WeakReference<Activity>(null)
    private var activityIsResumed = false

    private var appOpenAd: AppOpenAd? = null
    private var appOpenUnitId = ""
    private var appOpenGeneration = -1L
    private var appOpenLoadedAt = 0L
    private var appOpenLoading = false
    private var appOpenLoadToken = 0L
    private var appOpenRetryAttempt = 0
    private var appOpenRetry: Runnable? = null
    private var appOpenPendingForForeground = false

    private var splashInterstitialAd: InterstitialAd? = null
    private var splashInterstitialUnitId = ""
    private var splashInterstitialGeneration = -1L
    private var splashInterstitialLoadedAt = 0L
    private var splashInterstitialLoading = false
    private var splashInterstitialLoadToken = 0L

    private var splashAppOpenAd: AppOpenAd? = null
    private var splashAppOpenUnitId = ""
    private var splashAppOpenGeneration = -1L
    private var splashAppOpenLoadedAt = 0L
    private var splashAppOpenLoading = false
    private var splashAppOpenLoadToken = 0L
    private var splashLoadingGate: PlacementGate? = null

    fun configure(activity: Activity, remoteSettings: SorenAdsSettings) {
        runOnMain {
            // Re-apply the binary policy at the final SDK boundary. This is deliberate
            // defense-in-depth in case a future caller passes raw bootstrap settings.
            val resolved = remoteSettings.forCurrentBuild().sanitized()
            if (resolved != settings) {
                configGeneration += 1
                settings = resolved
                consentRequestedGeneration = -1L
                invalidateLoadedAds()
                signalBannerReload()
                Log.i(TAG, "Ad configuration updated (generation=$configGeneration, test=${resolved.testMode})")
            }

            if (!settings.enabled) {
                invalidateLoadedAds()
                return@runOnMain
            }

            // On filtered networks AdMob and UMP must not start until the local Xray proxy is
            // reachable. This mirrors the load-by-VPN flow and avoids direct DNS poisoning.
            if (!vpnRouteReadySnapshot) {
                Log.i(TAG, "Ad SDK waiting for the VPN ad route")
                return@runOnMain
            }

            val appOpenPlacement = settings.placements.appOpen
            if (!appOpenShownThisSession.get() && isCurrentResumedActivity(activity) &&
                shouldAttempt(activity, APP_OPEN, appOpenPlacement)
            ) {
                appOpenPendingForForeground = true
            }

            initializeAdsWhenRouted(activity)
        }
    }

    fun onActivityResumed(activity: Activity) {
        runOnMain {
            resumedActivity = WeakReference(activity)
            activityIsResumed = true
            if (!settings.enabled) return@runOnMain

            if (!vpnRouteReadySnapshot) return@runOnMain

            if (temporarySplashRouteActive) {
                if (sdkReadySnapshot) resumeSplashGateIfReady()
                else initializeAdsWhenRouted(activity)
                return@runOnMain
            }

            if (sdkReadySnapshot) {
                preloadAll(activity.applicationContext)
                val placement = settings.placements.appOpen
                if (!appOpenShownThisSession.get() && shouldAttempt(activity, APP_OPEN, placement)) {
                    appOpenPendingForForeground = true
                    if (!showAppOpenIfReady(activity, placement)) {
                        preloadAppOpen(activity.applicationContext, placement)
                    }
                }
            } else {
                initializeAdsWhenRouted(activity)
            }
        }
    }

    fun onActivityPaused(activity: Activity) {
        runOnMain {
            if (resumedActivity.get() === activity) {
                activityIsResumed = false
                resumedActivity.clear()
            }
        }
    }

    /** Releases callbacks retained for an Activity which can no longer display an ad. */
    fun onActivityDestroyed(activity: Activity) {
        runOnMain {
            placementGates.values
                .filter { it.activity.get() === activity }
                .map { it.key }
                .forEach { key -> finishPlacementGate(key, "activity_destroyed") }
            if (resumedActivity.get() === activity) {
                activityIsResumed = false
                resumedActivity.clear()
            }
        }
    }

    fun showBeforeConnection(activity: Activity, onContinue: () -> Unit) {
        runOnMain {
            val continued = AtomicBoolean(false)
            val continueOnce = { if (continued.compareAndSet(false, true)) onContinue() }
            val placement = settings.placements.beforeConnect

            if (!sdkReadySnapshot || !settings.enabled || !vpnRouteReadySnapshot ||
                !isCurrentResumedActivity(activity) ||
                !shouldAttempt(activity, BEFORE_CONNECT, placement)
            ) {
                if (sdkReadySnapshot) preloadInterstitial(activity.applicationContext, BEFORE_CONNECT, placement)
                continueOnce()
                return@runOnMain
            }

            val slot = slot(BEFORE_CONNECT)
            val ad = takeFreshInterstitial(slot, placement)
            if (ad == null) {
                preloadInterstitial(activity.applicationContext, BEFORE_CONNECT, placement)
                continueOnce()
                return@runOnMain
            }

            if (!showInterstitial(activity, BEFORE_CONNECT, placement, ad, continueOnce)) {
                continueOnce()
            }
        }
    }

    /**
     * Called only after the user-requested VPN core reports a real successful start.
     *
     * The core is already running so AdMob can use the tunnel, while [onFinished] keeps the
     * visible state at Connecting. The continuation is fail-open and exactly-once for every
     * terminal path: skipped/disabled, dismiss, no-fill/error, load timeout, show failure, route
     * loss, or Activity destruction.
     */
    fun showAfterSuccessfulConnection(activity: Activity, onFinished: () -> Unit = {}) {
        runOnMain {
            if (!BuildConfig.ADMOB_COMPILED_IN || !settings.enabled) {
                onFinished()
                return@runOnMain
            }
            beginPlacementGate(
                activity = activity,
                key = AFTER_CONNECT,
                placement = settings.placements.afterConnect,
                onFinished = onFinished
            )
            vpnConnected = true
            vpnRouteReadySnapshot = false
            vpnRouteGeneration += 1L
            val routeGeneration = vpnRouteGeneration
            SorenAdTrafficProxy.setVpnConnectedWithResult(activity.applicationContext, true) { result ->
                if (routeGeneration != vpnRouteGeneration) return@setVpnConnectedWithResult
                if (!vpnConnected) {
                    finishPlacementGate(AFTER_CONNECT, "vpn_disconnected")
                    return@setVpnConnectedWithResult
                }
                if (result != SorenAdRouteResult.FULL_READY) {
                    finishPlacementGate(AFTER_CONNECT, "vpn_route_not_fully_ready")
                    return@setVpnConnectedWithResult
                }
                vpnRouteReadySnapshot = true
                initializeAdsWhenRouted(activity)
                showAfterSuccessfulConnectionWithProxy(activity)
            }
        }
    }

    /**
     * Routes only the dedicated full-screen splash interstitial through a temporary ProxyOnly
     * core. Normal app-open/after-connect preloads stay suspended so they cannot race it.
     */
    fun showSplashThroughTemporaryRoute(activity: Activity, onFinished: () -> Unit) {
        runOnMain {
            if (!BuildConfig.ADMOB_COMPILED_IN || !settings.enabled) {
                onFinished()
                return@runOnMain
            }
            val placement = settings.placements.splash
            val gate = beginPlacementGate(activity, SPLASH, placement, onFinished)
            if (!settings.enabled || !placement.enabled) {
                finishPlacementGate(SPLASH, "splash_unavailable")
                return@runOnMain
            }

            temporarySplashRouteActive = true
            temporarySplashRouteGeneration += 1L
            val routeGeneration = temporarySplashRouteGeneration
            vpnRouteReadySnapshot = false
            SorenAdTrafficProxy.setVpnConnectedWithResult(activity.applicationContext, true) { result ->
                if (routeGeneration == temporarySplashRouteGeneration &&
                    temporarySplashRouteActive && placementGates[SPLASH] === gate
                ) {
                    if (result != SorenAdRouteResult.FULL_READY) {
                        finishPlacementGate(SPLASH, "splash_route_not_fully_ready")
                        return@setVpnConnectedWithResult
                    }
                    vpnRouteReadySnapshot = true
                    initializeAdsWhenRouted(activity)
                    requestGatedSplash(activity, gate, placement)
                }
            }
        }
    }

    /** Backward-compatible name for the splash coordinator. */
    fun showSplash(activity: Activity, onFinished: () -> Unit) =
        showSplashThroughTemporaryRoute(activity, onFinished)

    /** Clears both Java and WebView routes before the coordinator starts the normal VPN flow. */
    fun closeTemporarySplashRoute(context: Context, onClosed: () -> Unit = {}) {
        runOnMain {
            temporarySplashRouteActive = false
            temporarySplashRouteGeneration += 1L
            finishPlacementGate(SPLASH, "temporary_route_closed")
            cancelSplashAdLoads()
            clearSplashInterstitialAd()
            clearSplashAppOpenAd()
            if (!vpnConnected) vpnRouteReadySnapshot = false
            SorenAdTrafficProxy.clearVpnRoute(context.applicationContext) {
                runOnMain(onClosed)
            }
        }
    }

    fun onTemporarySplashRouteClosed(context: Context) = closeTemporarySplashRoute(context)

    /** Keeps the application-level ad route synchronized with the actual VPN state. */
    fun onVpnConnectionChanged(context: Context, connected: Boolean) {
        runOnMain {
            if (!BuildConfig.ADMOB_COMPILED_IN) {
                vpnConnected = connected
                vpnRouteReadySnapshot = false
                return@runOnMain
            }
            if (temporarySplashRouteActive && !connected) {
                Log.d(TAG, "Ignoring main-state disconnect while temporary splash route is active")
                return@runOnMain
            }
            vpnConnected = connected
            vpnRouteReadySnapshot = false
            vpnRouteGeneration += 1L
            val routeGeneration = vpnRouteGeneration
            if (!connected) {
                consentRequestedGeneration = -1L
                invalidateLoadedAds()
                signalBannerReload()
            }
            SorenAdTrafficProxy.setVpnConnectedWithResult(
                context.applicationContext,
                connected
            ) { result ->
                if (routeGeneration != vpnRouteGeneration || !connected || !vpnConnected ||
                    !settings.enabled
                ) return@setVpnConnectedWithResult
                if (result != SorenAdRouteResult.FULL_READY) return@setVpnConnectedWithResult
                vpnRouteReadySnapshot = true
                resumedActivity.get()?.takeIf { activityIsResumed }?.let(::initializeAdsWhenRouted)
                if (!sdkReadySnapshot) return@setVpnConnectedWithResult
                signalBannerReload()
                resetRetriesAndPreload(context.applicationContext)
            }
        }
    }

    private fun showAfterSuccessfulConnectionWithProxy(activity: Activity) {
        signalBannerReload()
        requestGatedInterstitial(activity, AFTER_CONNECT, settings.placements.afterConnect)
    }

    private fun requestGatedInterstitial(
        activity: Activity,
        key: String,
        placement: SorenAdPlacement
    ) {
        val gate = placementGates[key] ?: return
        if (SystemClock.elapsedRealtime() >= gate.deadlineElapsed) {
            finishPlacementGate(key, "load_deadline_reached")
            return
        }
        if (!settings.enabled || !vpnRouteReadySnapshot) {
            finishPlacementGate(key, "ads_or_route_disabled")
            return
        }

        // A blocked cold-start request can now be retried on the newly available network.
        if (sdkReadySnapshot) resetRetriesAndPreload(activity.applicationContext)
        if (!isCurrentResumedActivity(activity) || !shouldAttempt(activity, key, placement)) {
            preloadInterstitial(activity.applicationContext, key, placement)
            finishPlacementGate(key, "placement_skipped")
            return
        }

        val slot = slot(key)
        val ad = takeFreshInterstitial(slot, placement)
        if (ad != null) {
            markPlacementGateShowing(key)
            if (!showInterstitial(
                    activity = activity,
                    key = key,
                    placement = placement,
                    ad = ad,
                    onFinished = { finishPlacementGate(key, "ad_finished") }
                )
            ) {
                slot.ad = ad
                slot.loadedAt = SystemClock.elapsedRealtime()
                finishPlacementGate(key, "show_unavailable")
            }
        } else {
            pendingShows[key] = PendingShow(
                activity = WeakReference(activity),
                generation = configGeneration,
                expiresAtElapsed = SystemClock.elapsedRealtime() + PENDING_SHOW_MAX_AGE_MS
            )
            preloadInterstitial(activity.applicationContext, key, placement)
        }
    }

    private fun requestGatedSplash(
        activity: Activity,
        gate: PlacementGate,
        placement: SorenAdPlacement
    ) {
        if (placementGates[SPLASH] !== gate) return
        if (SystemClock.elapsedRealtime() >= gate.deadlineElapsed) {
            finishPlacementGate(SPLASH, "splash_load_deadline_reached")
            return
        }
        if (!settings.enabled || !vpnRouteReadySnapshot) {
            finishPlacementGate(SPLASH, "splash_ads_or_route_disabled")
            return
        }
        if (gate.attemptApproved == null) {
            gate.attemptApproved = isCurrentResumedActivity(activity) &&
                shouldAttempt(activity, SPLASH, placement)
        }
        if (gate.attemptApproved != true) {
            finishPlacementGate(SPLASH, "splash_placement_skipped")
            return
        }
        if (!sdkReadySnapshot) return

        when (placement.format.lowercase()) {
            "interstitial" -> requestGatedSplashInterstitial(activity, gate, placement)
            "app_open" -> requestGatedSplashAppOpen(activity, gate, placement)
            else -> finishPlacementGate(SPLASH, "splash_format_invalid")
        }
    }

    private fun resumeSplashGateIfReady() {
        val gate = placementGates[SPLASH] ?: return
        val activity = gate.activity.get()
        if (activity == null) {
            finishPlacementGate(SPLASH, "splash_activity_unavailable")
            return
        }
        requestGatedSplash(activity, gate, settings.placements.splash)
    }

    private fun requestGatedSplashInterstitial(
        activity: Activity,
        gate: PlacementGate,
        placement: SorenAdPlacement
    ) {
        if (placementGates[SPLASH] !== gate || splashInterstitialLoading) return
        val unitId = placement.effectiveUnitId()
        if (unitId.isBlank()) {
            finishPlacementGate(SPLASH, "splash_unit_missing")
            return
        }

        val cached = splashInterstitialAd
        if (cached != null && splashInterstitialUnitId == unitId &&
            splashInterstitialGeneration == configGeneration &&
            isFresh(splashInterstitialLoadedAt, INTERSTITIAL_MAX_AGE_MS)
        ) {
            showGatedSplashInterstitial(activity, gate, placement, cached)
            return
        }

        clearSplashInterstitialAd()
        splashInterstitialLoading = true
        splashLoadingGate = gate
        splashInterstitialUnitId = unitId
        splashInterstitialGeneration = configGeneration
        splashInterstitialLoadToken += 1L
        val token = splashInterstitialLoadToken
        val generation = configGeneration
        Log.d(TAG, "Loading splash full-screen interstitial")

        InterstitialAd.load(
            activity.applicationContext,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    if (!splashInterstitialLoading || splashInterstitialLoadToken != token ||
                        generation != configGeneration || splashLoadingGate !== gate ||
                        placementGates[SPLASH] !== gate
                    ) return
                    if (SystemClock.elapsedRealtime() >= gate.deadlineElapsed) {
                        splashInterstitialLoading = false
                        splashLoadingGate = null
                        clearSplashInterstitialAd()
                        finishPlacementGate(SPLASH, "splash_loaded_after_deadline")
                        return
                    }
                    splashInterstitialLoading = false
                    splashLoadingGate = null
                    splashInterstitialAd = ad
                    splashInterstitialLoadedAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Splash full-screen interstitial loaded")
                    showGatedSplashInterstitial(activity, gate, placement, ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (splashInterstitialLoadToken != token || splashLoadingGate !== gate) return
                    splashInterstitialLoading = false
                    splashLoadingGate = null
                    clearSplashInterstitialAd()
                    Log.w(TAG, "Splash interstitial load failed ${error.code}/${error.domain}: ${error.message}")
                    finishPlacementGate(SPLASH, "splash_load_failed")
                }
            }
        )
    }

    private fun showGatedSplashInterstitial(
        activity: Activity,
        gate: PlacementGate,
        placement: SorenAdPlacement,
        ad: InterstitialAd
    ) {
        if (placementGates[SPLASH] !== gate || !isCurrentResumedActivity(activity) ||
            !fullScreenShowing.compareAndSet(false, true)
        ) {
            finishPlacementGate(SPLASH, "splash_show_unavailable")
            return
        }

        markPlacementGateShowing(SPLASH)
        splashInterstitialAd = null
        val finished = AtomicBoolean(false)
        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            releaseFullScreen()
            finishPlacementGate(SPLASH, reason)
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "Splash full-screen interstitial shown")
            }

            override fun onAdImpression() {
                markShown(activity, SPLASH)
            }

            override fun onAdDismissedFullScreenContent() {
                finish("splash_dismissed")
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Splash interstitial show failed ${error.code}/${error.domain}: ${error.message}")
                finish("splash_show_failed")
            }
        }

        // Match Zaal's immersive SecondSplashActivity experience: the ad owns the whole display,
        // including hiding system bars where the Google Mobile Ads SDK supports it.
        runCatching {
            ad.setImmersiveMode(true)
            ad.show(activity)
        }.onFailure { error ->
            Log.e(TAG, "Splash interstitial show threw", error)
            finish("splash_show_threw")
        }

        // SDK callbacks are normally reliable, but never hold the splash forever if one is lost.
        mainHandler.postDelayed(
            { finish("splash_show_timeout") },
            FULL_SCREEN_CALLBACK_TIMEOUT_MS
        )
    }

    /**
     * Production-safe cold-start path. Google requires App Open rather than Interstitial on app
     * load; it is still displayed edge-to-edge and owns the same blocking splash gate.
     */
    private fun requestGatedSplashAppOpen(
        activity: Activity,
        gate: PlacementGate,
        placement: SorenAdPlacement
    ) {
        if (placementGates[SPLASH] !== gate || splashAppOpenLoading) return
        val unitId = placement.effectiveUnitId()
        if (unitId.isBlank()) {
            finishPlacementGate(SPLASH, "splash_unit_missing")
            return
        }

        val cached = splashAppOpenAd
        if (cached != null && splashAppOpenUnitId == unitId &&
            splashAppOpenGeneration == configGeneration &&
            isFresh(splashAppOpenLoadedAt, APP_OPEN_MAX_AGE_MS)
        ) {
            showGatedSplashAppOpen(activity, gate, cached)
            return
        }

        clearSplashAppOpenAd()
        splashAppOpenLoading = true
        splashLoadingGate = gate
        splashAppOpenUnitId = unitId
        splashAppOpenGeneration = configGeneration
        splashAppOpenLoadToken += 1L
        val token = splashAppOpenLoadToken
        val generation = configGeneration
        Log.d(TAG, "Loading splash immersive app-open ad")

        AppOpenAd.load(
            activity.applicationContext,
            unitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    if (!splashAppOpenLoading || splashAppOpenLoadToken != token ||
                        generation != configGeneration || splashLoadingGate !== gate ||
                        placementGates[SPLASH] !== gate
                    ) return
                    if (SystemClock.elapsedRealtime() >= gate.deadlineElapsed) {
                        splashAppOpenLoading = false
                        splashLoadingGate = null
                        clearSplashAppOpenAd()
                        finishPlacementGate(SPLASH, "splash_loaded_after_deadline")
                        return
                    }
                    splashAppOpenLoading = false
                    splashLoadingGate = null
                    splashAppOpenAd = ad
                    splashAppOpenLoadedAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Splash immersive app-open ad loaded")
                    showGatedSplashAppOpen(activity, gate, ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (splashAppOpenLoadToken != token || splashLoadingGate !== gate) return
                    splashAppOpenLoading = false
                    splashLoadingGate = null
                    clearSplashAppOpenAd()
                    Log.w(TAG, "Splash app-open load failed ${error.code}/${error.domain}: ${error.message}")
                    finishPlacementGate(SPLASH, "splash_load_failed")
                }
            }
        )
    }

    private fun showGatedSplashAppOpen(
        activity: Activity,
        gate: PlacementGate,
        ad: AppOpenAd
    ) {
        if (placementGates[SPLASH] !== gate || !isCurrentResumedActivity(activity) ||
            !fullScreenShowing.compareAndSet(false, true)
        ) {
            finishPlacementGate(SPLASH, "splash_show_unavailable")
            return
        }

        markPlacementGateShowing(SPLASH)
        splashAppOpenAd = null
        val finished = AtomicBoolean(false)
        fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            releaseFullScreen()
            finishPlacementGate(SPLASH, reason)
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "Splash immersive app-open ad shown")
            }

            override fun onAdImpression() {
                markShown(activity, SPLASH)
            }

            override fun onAdDismissedFullScreenContent() {
                finish("splash_dismissed")
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Splash app-open show failed ${error.code}/${error.domain}: ${error.message}")
                finish("splash_show_failed")
            }
        }

        runCatching {
            ad.setImmersiveMode(true)
            ad.show(activity)
        }.onFailure { error ->
            Log.e(TAG, "Splash app-open show threw", error)
            finish("splash_show_threw")
        }

        mainHandler.postDelayed(
            { finish("splash_show_timeout") },
            FULL_SCREEN_CALLBACK_TIMEOUT_MS
        )
    }

    private fun initializeAdsWhenRouted(activity: Activity) {
        if (!settings.enabled || !vpnRouteReadySnapshot) return
        if (!settings.umpRequired) {
            ensureSdkInitialized(activity.applicationContext)
        } else {
            requestConsentThenInitialize(activity)
        }
    }

    private fun requestConsentThenInitialize(activity: Activity) {
        if (!vpnRouteReadySnapshot) return
        if (consentRequestedGeneration == configGeneration) return
        consentRequestedGeneration = configGeneration
        val generation = configGeneration
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (generation != configGeneration || !settings.enabled || !vpnRouteReadySnapshot) {
                    return@requestConsentInfoUpdate
                }
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (generation != configGeneration || !settings.enabled || !vpnRouteReadySnapshot) {
                        return@loadAndShowConsentFormIfRequired
                    }
                    if (formError != null) {
                        Log.w(TAG, "UMP form error ${formError.errorCode}: ${formError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        ensureSdkInitialized(activity.applicationContext)
                    } else {
                        Log.w(TAG, "Consent does not allow ad requests")
                    }
                }
            },
            { requestError ->
                Log.w(TAG, "UMP update error ${requestError.errorCode}: ${requestError.message}")
                if (generation == configGeneration && settings.enabled && vpnRouteReadySnapshot &&
                    consentInformation.canRequestAds()
                ) {
                    ensureSdkInitialized(activity.applicationContext)
                }
            }
        )
        if (consentInformation.canRequestAds()) ensureSdkInitialized(activity.applicationContext)
    }

    private fun ensureSdkInitialized(context: Context) {
        if (!BuildConfig.ADMOB_COMPILED_IN || !settings.enabled) return
        if (!vpnRouteReadySnapshot) return
        if (sdkReadySnapshot) {
            if (temporarySplashRouteActive) resumeSplashGateIfReady()
            else preloadAll(context.applicationContext)
            return
        }
        if (!initializationStarted.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        Thread({
            runCatching {
                MobileAds.initialize(appContext) { status ->
                    mainHandler.post {
                        sdkReadySnapshot = true
                        val adapterCount = status.adapterStatusMap.size
                        Log.i(TAG, "Mobile Ads initialized ($adapterCount adapters)")
                        if (settings.enabled && vpnRouteReadySnapshot) {
                            if (temporarySplashRouteActive) {
                                resumeSplashGateIfReady()
                            } else {
                                signalBannerReload()
                                preloadAll(appContext)
                            }
                        }
                    }
                }
            }.onFailure { error ->
                mainHandler.post {
                    initializationStarted.set(false)
                    Log.e(TAG, "Mobile Ads initialization failed", error)
                    if (vpnRouteReadySnapshot) {
                        mainHandler.postDelayed({ ensureSdkInitialized(appContext) }, RETRY_DELAYS_MS.first())
                    }
                }
            }
        }, "tornado-ads-init").start()
    }

    private fun preloadAll(context: Context) {
        if (!sdkReadySnapshot || !settings.enabled || !vpnRouteReadySnapshot) return
        preloadInterstitial(context, BEFORE_CONNECT, settings.placements.beforeConnect)
        preloadInterstitial(context, AFTER_CONNECT, settings.placements.afterConnect)
        preloadAppOpen(context, settings.placements.appOpen)
    }

    private fun resetRetriesAndPreload(context: Context) {
        slots.values.forEach { slot ->
            slot.retryRunnable?.let(mainHandler::removeCallbacks)
            slot.retryRunnable = null
            slot.retryAttempt = 0
        }
        appOpenRetry?.let(mainHandler::removeCallbacks)
        appOpenRetry = null
        appOpenRetryAttempt = 0
        preloadAll(context)
    }

    private fun preloadInterstitial(context: Context, key: String, placement: SorenAdPlacement) {
        if (!sdkReadySnapshot || !settings.enabled || !vpnRouteReadySnapshot || !placement.enabled) return
        val unitId = placement.effectiveUnitId()
        if (unitId.isBlank()) return

        val slot = slot(key)
        if (slot.loading) return
        if (isFresh(slot.loadedAt, INTERSTITIAL_MAX_AGE_MS) &&
            slot.ad != null && slot.unitId == unitId && slot.generation == configGeneration
        ) return

        slot.clearAd()
        slot.loading = true
        slot.unitId = unitId
        slot.generation = configGeneration
        slot.loadToken += 1
        val loadToken = slot.loadToken
        val generation = configGeneration
        Log.d(TAG, "Loading $key interstitial")

        val timeout = Runnable {
            if (slot.loading && slot.loadToken == loadToken) {
                slot.loading = false
                slot.loadToken += 1
                Log.w(TAG, "$key interstitial load timed out")
                finishPendingPlacementGate(key, "load_timeout")
                scheduleInterstitialRetry(context, key, placement, slot)
            }
        }
        mainHandler.postDelayed(timeout, settings.loadTimeoutMs.toLong())

        InterstitialAd.load(
            context.applicationContext,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    mainHandler.removeCallbacks(timeout)
                    if (!slot.loading || slot.loadToken != loadToken ||
                        generation != configGeneration || unitId != placement.effectiveUnitId()
                    ) return
                    slot.loading = false
                    slot.ad = ad
                    slot.loadedAt = SystemClock.elapsedRealtime()
                    slot.retryAttempt = 0
                    slot.retryRunnable = null
                    Log.i(TAG, "$key interstitial loaded")
                    consumePendingShow(key, placement)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    mainHandler.removeCallbacks(timeout)
                    if (slot.loadToken != loadToken || generation != configGeneration) return
                    slot.loading = false
                    slot.ad = null
                    Log.w(TAG, "$key load failed ${error.code}/${error.domain}: ${error.message}")
                    finishPendingPlacementGate(key, "load_failed")
                    scheduleInterstitialRetry(context, key, placement, slot)
                }
            }
        )
    }

    private fun consumePendingShow(key: String, placement: SorenAdPlacement) {
        val pending = pendingShows.remove(key) ?: return
        val gate = placementGates[key]
        if (gate == null || SystemClock.elapsedRealtime() >= gate.deadlineElapsed) {
            finishPlacementGate(key, "loaded_after_deadline")
            return
        }
        val activity = pending.activity.get()
        if (activity == null) {
            finishPlacementGate(key, "activity_unavailable")
            return
        }
        if (pending.generation != configGeneration ||
            pending.expiresAtElapsed < SystemClock.elapsedRealtime() ||
            !isCurrentResumedActivity(activity)
        ) {
            finishPlacementGate(key, "pending_show_expired")
            return
        }
        val ad = takeFreshInterstitial(slot(key), placement)
        if (ad == null) {
            finishPlacementGate(key, "loaded_ad_unavailable")
            return
        }
        markPlacementGateShowing(key)
        if (!showInterstitial(
                activity = activity,
                key = key,
                placement = placement,
                ad = ad,
                onFinished = { finishPlacementGate(key, "ad_finished") }
            )
        ) {
            slot(key).ad = ad
            slot(key).loadedAt = SystemClock.elapsedRealtime()
            finishPlacementGate(key, "show_unavailable")
        }
    }

    private fun showInterstitial(
        activity: Activity,
        key: String,
        placement: SorenAdPlacement,
        ad: InterstitialAd,
        onFinished: (() -> Unit)?
    ): Boolean {
        if (!isCurrentResumedActivity(activity) || !fullScreenShowing.compareAndSet(false, true)) {
            return false
        }

        val finished = AtomicBoolean(false)
        fun finish(reload: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            releaseFullScreen()
            if (reload) preloadInterstitial(activity.applicationContext, key, placement)
            onFinished?.invoke()
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "$key interstitial shown")
            }

            override fun onAdImpression() {
                markShown(activity, key)
            }

            override fun onAdDismissedFullScreenContent() {
                finish(reload = true)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "$key show failed ${error.code}/${error.domain}: ${error.message}")
                finish(reload = true)
            }
        }

        return runCatching {
            ad.show(activity)
            if (onFinished != null) {
                mainHandler.postDelayed({
                    if (finished.compareAndSet(false, true)) {
                        // Never leave VPN connection waiting for a lost SDK callback.
                        releaseFullScreen()
                        preloadInterstitial(activity.applicationContext, key, placement)
                        onFinished.invoke()
                    }
                }, FULL_SCREEN_CALLBACK_TIMEOUT_MS)
            }
        }.onFailure { error ->
            Log.e(TAG, "$key show threw", error)
            finish(reload = true)
        }.isSuccess
    }

    private fun scheduleInterstitialRetry(
        context: Context,
        key: String,
        placement: SorenAdPlacement,
        slot: InterstitialSlot
    ) {
        if (!settings.enabled || !vpnRouteReadySnapshot || !placement.enabled || slot.retryRunnable != null) return
        val retryIndex = slot.retryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
        val delayMs = RETRY_DELAYS_MS[retryIndex]
        slot.retryAttempt = (slot.retryAttempt + 1).coerceAtMost(RETRY_DELAYS_MS.size)
        val generation = configGeneration
        val retry = Runnable {
            slot.retryRunnable = null
            if (generation == configGeneration) preloadInterstitial(context, key, placement)
        }
        slot.retryRunnable = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun preloadAppOpen(context: Context, placement: SorenAdPlacement) {
        if (!sdkReadySnapshot || !settings.enabled || !vpnRouteReadySnapshot || !placement.enabled ||
            appOpenShownThisSession.get() || appOpenLoading
        ) return
        val unitId = placement.effectiveUnitId()
        if (unitId.isBlank()) return
        if (appOpenAd != null && appOpenUnitId == unitId && appOpenGeneration == configGeneration &&
            isFresh(appOpenLoadedAt, APP_OPEN_MAX_AGE_MS)
        ) return

        clearAppOpenAd()
        appOpenLoading = true
        appOpenUnitId = unitId
        appOpenGeneration = configGeneration
        appOpenLoadToken += 1
        val token = appOpenLoadToken
        val generation = configGeneration

        val timeout = Runnable {
            if (appOpenLoading && appOpenLoadToken == token) {
                appOpenLoading = false
                appOpenLoadToken += 1
                Log.w(TAG, "App-open load timed out")
                scheduleAppOpenRetry(context, placement)
            }
        }
        mainHandler.postDelayed(timeout, settings.loadTimeoutMs.toLong())

        AppOpenAd.load(
            context.applicationContext,
            unitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    mainHandler.removeCallbacks(timeout)
                    if (!appOpenLoading || appOpenLoadToken != token || generation != configGeneration) return
                    appOpenLoading = false
                    appOpenAd = ad
                    appOpenLoadedAt = SystemClock.elapsedRealtime()
                    appOpenRetryAttempt = 0
                    appOpenRetry = null
                    Log.i(TAG, "App-open ad loaded")
                    val activity = resumedActivity.get()
                    if (appOpenPendingForForeground && activity != null) {
                        showAppOpenIfReady(activity, placement)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    mainHandler.removeCallbacks(timeout)
                    if (appOpenLoadToken != token || generation != configGeneration) return
                    appOpenLoading = false
                    appOpenAd = null
                    Log.w(TAG, "App-open load failed ${error.code}/${error.domain}: ${error.message}")
                    scheduleAppOpenRetry(context, placement)
                }
            }
        )
    }

    private fun showAppOpenIfReady(activity: Activity, placement: SorenAdPlacement): Boolean {
        val ad = appOpenAd ?: return false
        if (!appOpenPendingForForeground || !isCurrentResumedActivity(activity) ||
            !isFresh(appOpenLoadedAt, APP_OPEN_MAX_AGE_MS)
        ) return false
        val now = SystemClock.elapsedRealtime()
        if (lastFullScreenClosedAt > 0L &&
            now - lastFullScreenClosedAt < FULL_SCREEN_GLOBAL_COOLDOWN_MS
        ) {
            // A splash/after-connect ad has just released this Activity. Never stack an
            // app-open ad immediately behind another full-screen placement.
            appOpenPendingForForeground = false
            return false
        }
        if (!fullScreenShowing.compareAndSet(false, true)) return false
        if (!appOpenShownThisSession.compareAndSet(false, true)) {
            fullScreenShowing.set(false)
            return false
        }

        appOpenPendingForForeground = false
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdImpression() {
                markShown(activity, APP_OPEN)
            }

            override fun onAdDismissedFullScreenContent() {
                releaseFullScreen()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "App-open show failed ${error.code}/${error.domain}: ${error.message}")
                releaseFullScreen()
                appOpenShownThisSession.set(false)
                preloadAppOpen(activity.applicationContext, placement)
            }
        }
        return runCatching { ad.show(activity) }.onFailure { error ->
            Log.e(TAG, "App-open show threw", error)
            releaseFullScreen()
            appOpenShownThisSession.set(false)
            preloadAppOpen(activity.applicationContext, placement)
        }.isSuccess
    }

    private fun scheduleAppOpenRetry(context: Context, placement: SorenAdPlacement) {
        if (!settings.enabled || !vpnRouteReadySnapshot || !placement.enabled ||
            appOpenRetry != null || appOpenShownThisSession.get()
        ) return
        val retryIndex = appOpenRetryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
        val delayMs = RETRY_DELAYS_MS[retryIndex]
        appOpenRetryAttempt = (appOpenRetryAttempt + 1).coerceAtMost(RETRY_DELAYS_MS.size)
        val generation = configGeneration
        val retry = Runnable {
            appOpenRetry = null
            if (generation == configGeneration) preloadAppOpen(context, placement)
        }
        appOpenRetry = retry
        mainHandler.postDelayed(retry, delayMs)
    }

    private fun shouldAttempt(context: Context, key: String, placement: SorenAdPlacement): Boolean {
        if (!placement.enabled || placement.effectiveUnitId().isBlank()
        ) return false
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val actions = preferences.getInt("${key}_actions", 0) + 1
        preferences.edit().putInt("${key}_actions", actions).apply()
        // Test builds intentionally exercise every action. Production interstitials keep at
        // least one user action between full-screen ads to avoid disruptive repeated display.
        val cadence = if (!settings.testMode &&
            (key == BEFORE_CONNECT || key == AFTER_CONNECT)
        ) {
            placement.everyNActions.coerceAtLeast(2)
        } else {
            placement.everyNActions
        }
        if (actions % cadence != 0) return false

        val now = System.currentTimeMillis()
        if (now - preferences.getLong("${key}_last_shown", 0L) < placement.cooldownSeconds * 1_000L) {
            return false
        }
        if (placement.maxPerDay > 0) {
            val today = now / MILLIS_PER_DAY
            val storedDay = preferences.getLong("${key}_day", -1L)
            val shownToday = if (storedDay == today) preferences.getInt("${key}_daily", 0) else 0
            if (shownToday >= placement.maxPerDay) return false
        }
        return true
    }

    private fun markShown(context: Context, key: String) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val today = now / MILLIS_PER_DAY
        val storedDay = preferences.getLong("${key}_day", -1L)
        val daily = if (storedDay == today) preferences.getInt("${key}_daily", 0) + 1 else 1
        preferences.edit()
            .putLong("${key}_last_shown", now)
            .putLong("${key}_day", today)
            .putInt("${key}_daily", daily)
            .apply()
    }

    private fun takeFreshInterstitial(slot: InterstitialSlot, placement: SorenAdPlacement): InterstitialAd? {
        val expectedId = placement.effectiveUnitId()
        if (slot.generation != configGeneration || slot.unitId != expectedId ||
            !isFresh(slot.loadedAt, INTERSTITIAL_MAX_AGE_MS)
        ) {
            slot.clearAd()
            return null
        }
        return slot.ad.also { slot.ad = null }
    }

    private fun isFresh(loadedAt: Long, maxAge: Long): Boolean =
        loadedAt > 0L && SystemClock.elapsedRealtime() - loadedAt < maxAge

    private fun isCurrentResumedActivity(activity: Activity): Boolean =
        activityIsResumed && resumedActivity.get() === activity &&
            !activity.isFinishing && !activity.isDestroyed

    private fun slot(key: String): InterstitialSlot = slots.getOrPut(key) { InterstitialSlot() }

    private fun beginPlacementGate(
        activity: Activity,
        key: String,
        placement: SorenAdPlacement,
        onFinished: () -> Unit
    ): PlacementGate {
        finishPlacementGate(key, "superseded")
        val totalLoadWindowMs = placement.timeoutMs.toLong() + ROUTE_SETUP_ALLOWANCE_MS
        val gate = PlacementGate(
            key = key,
            activity = WeakReference(activity),
            completion = OneShotAdCompletion(onFinished),
            deadlineElapsed = SystemClock.elapsedRealtime() + totalLoadWindowMs
        )
        val timeout = Runnable {
            if (placementGates[key] !== gate || gate.showing) return@Runnable
            pendingShows.remove(key)
            Log.w(TAG, "$key ad gate load timed out")
            finishPlacementGate(key, "gate_load_timeout")
        }
        gate.loadTimeout = timeout
        placementGates[key] = gate
        mainHandler.postDelayed(timeout, totalLoadWindowMs)
        return gate
    }

    /** Stops the load deadline once a full-screen ad owns the Activity. */
    private fun markPlacementGateShowing(key: String) {
        val gate = placementGates[key] ?: return
        gate.showing = true
        gate.loadTimeout?.let(mainHandler::removeCallbacks)
        gate.loadTimeout = null
    }

    private fun finishPendingPlacementGate(key: String, reason: String) {
        if (pendingShows.remove(key) != null) {
            finishPlacementGate(key, reason)
        }
    }

    private fun finishPlacementGate(key: String, reason: String) {
        val gate = placementGates.remove(key) ?: return
        pendingShows.remove(key)
        gate.loadTimeout?.let(mainHandler::removeCallbacks)
        gate.loadTimeout = null
        if (gate.showing) releaseFullScreen()
        if (key == SPLASH && splashLoadingGate === gate) {
            cancelSplashAdLoads()
        }
        if (gate.completion.complete()) {
            Log.i(TAG, "$key ad gate completed ($reason)")
        }
    }

    private fun finishAllPlacementGates(reason: String) {
        placementGates.keys.toList().forEach { key -> finishPlacementGate(key, reason) }
    }

    private fun releaseFullScreen() {
        if (fullScreenShowing.getAndSet(false)) {
            lastFullScreenClosedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun invalidateLoadedAds() {
        slots.values.forEach { slot ->
            slot.retryRunnable?.let(mainHandler::removeCallbacks)
            slot.retryRunnable = null
            slot.loading = false
            slot.loadToken += 1
            slot.clearAd()
        }
        finishAllPlacementGates("ads_invalidated")
        pendingShows.clear()
        appOpenRetry?.let(mainHandler::removeCallbacks)
        appOpenRetry = null
        appOpenLoading = false
        appOpenLoadToken += 1
        clearAppOpenAd()
        cancelSplashAdLoads()
        clearSplashInterstitialAd()
        clearSplashAppOpenAd()
    }

    private fun clearAppOpenAd() {
        appOpenAd = null
        appOpenLoadedAt = 0L
        appOpenUnitId = ""
        appOpenGeneration = -1L
    }

    private fun cancelSplashAdLoads() {
        splashInterstitialLoading = false
        splashInterstitialLoadToken += 1L
        splashAppOpenLoading = false
        splashAppOpenLoadToken += 1L
        splashLoadingGate = null
    }

    private fun clearSplashInterstitialAd() {
        splashInterstitialAd = null
        splashInterstitialLoadedAt = 0L
        splashInterstitialUnitId = ""
        splashInterstitialGeneration = -1L
    }

    private fun clearSplashAppOpenAd() {
        splashAppOpenAd = null
        splashAppOpenLoadedAt = 0L
        splashAppOpenUnitId = ""
        splashAppOpenGeneration = -1L
    }

    private fun signalBannerReload() {
        bannerReloadSnapshot += 1L
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun SorenAdPlacement.effectiveUnitId(): String = unitId

    private fun SorenAdsSettings.sanitized(): SorenAdsSettings = copy(
        bannerUnitId = bannerUnitId.trim().take(180),
        interstitialUnitId = interstitialUnitId.trim().take(180),
        rewardedUnitId = rewardedUnitId.trim().take(180),
        interstitialEveryConnections = interstitialEveryConnections.coerceIn(1, 100),
        requestTimeoutMs = requestTimeoutMs.coerceIn(1_000, 60_000),
        loadTimeoutMs = loadTimeoutMs.coerceIn(1_000, 60_000),
        placements = SorenAdPlacements(
            beforeConnect = placements.beforeConnect.sanitized(),
            afterConnect = placements.afterConnect.sanitized(),
            // Test mode intentionally mirrors Zaal's full-screen Interstitial. Production uses
            // immersive App Open because AdMob disallows Interstitial ads at app launch.
            splash = placements.splash.sanitized().copy(
                format = if (testMode) "interstitial" else "app_open"
            ),
            appOpen = placements.appOpen.sanitized()
        )
    )

    private fun SorenAdPlacement.sanitized(): SorenAdPlacement = copy(
        unitId = unitId.trim().take(180),
        everyNActions = everyNActions.coerceIn(1, 100),
        cooldownSeconds = cooldownSeconds.coerceIn(0, 86_400),
        timeoutMs = timeoutMs.coerceIn(1_000, 60_000),
        maxPerDay = maxPerDay.coerceIn(0, 1_000)
    )

    private data class InterstitialSlot(
        var ad: InterstitialAd? = null,
        var unitId: String = "",
        var generation: Long = -1L,
        var loadedAt: Long = 0L,
        var loading: Boolean = false,
        var loadToken: Long = 0L,
        var retryAttempt: Int = 0,
        var retryRunnable: Runnable? = null
    ) {
        fun clearAd() {
            ad = null
            loadedAt = 0L
        }
    }

    private data class PendingShow(
        val activity: WeakReference<Activity>,
        val generation: Long,
        val expiresAtElapsed: Long
    )

    private data class PlacementGate(
        val key: String,
        val activity: WeakReference<Activity>,
        val completion: OneShotAdCompletion,
        val deadlineElapsed: Long,
        var loadTimeout: Runnable? = null,
        var showing: Boolean = false,
        var attemptApproved: Boolean? = null
    )
}

/** Bottom-anchored adaptive banner with lifecycle handling and exponential retry. */
@Composable
fun SorenBannerAd(settings: SorenAdsSettings, modifier: Modifier = Modifier) {
    if (!BuildConfig.ADMOB_COMPILED_IN) return
    val sdkReady = SorenAds.sdkReady
    val vpnRouteReady = SorenAds.vpnRouteReady
    val managerReload = SorenAds.bannerReloadGeneration
    val unitId = settings.bannerUnitId.trim()
    if (!settings.enabled || !sdkReady || !vpnRouteReady || unitId.isBlank()) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        var retryAttempt by remember(unitId, widthDp, managerReload) { mutableIntStateOf(0) }
        var localReload by remember(unitId, widthDp, managerReload) { mutableIntStateOf(0) }

        LaunchedEffect(retryAttempt) {
            if (retryAttempt > 0) {
                val delays = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
                delay(delays[(retryAttempt - 1).coerceAtMost(delays.lastIndex)])
                localReload += 1
            }
        }

        val adView = remember(unitId, widthDp, managerReload, localReload) {
            AdView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adUnitId = unitId
                setAdSize(AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp))
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        retryAttempt = 0
                        Log.i("TornadoAds", "Adaptive banner loaded")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(
                            "TornadoAds",
                            "Banner load failed ${error.code}/${error.domain}: ${error.message}"
                        )
                        retryAttempt = (retryAttempt + 1).coerceAtMost(5)
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        }

        AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth())

        DisposableEffect(lifecycleOwner, adView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> adView.resume()
                    Lifecycle.Event.ON_PAUSE -> adView.pause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                adView.destroy()
            }
        }
    }
}
