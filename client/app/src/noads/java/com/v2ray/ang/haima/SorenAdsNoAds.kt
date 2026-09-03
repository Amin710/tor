@file:Suppress("UNUSED_PARAMETER")

package com.v2ray.ang.haima

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Exactly-once continuation shared by the connection presentation tests. */
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
 * Publishing implementation for an explicitly ad-free binary.
 *
 * Every continuation completes immediately and no advertising SDK, proxy, timeout, permission,
 * or remote placement can delay bootstrap or the VPN connection state.
 */
object SorenAds {
    internal const val FULL_SCREEN_CALLBACK_TIMEOUT_MS = 90_000L

    val sdkReady: Boolean = false
    val vpnRouteReady: Boolean = false
    val bannerReloadGeneration: Long = 0L

    fun configure(activity: Activity, remoteSettings: SorenAdsSettings) = Unit

    fun onActivityResumed(activity: Activity) = Unit

    fun onActivityPaused(activity: Activity) = Unit

    fun onActivityDestroyed(activity: Activity) = Unit

    fun showBeforeConnection(activity: Activity, onContinue: () -> Unit) = onContinue()

    fun showAfterSuccessfulConnection(activity: Activity, onFinished: () -> Unit = {}) =
        onFinished()

    fun showSplashThroughTemporaryRoute(activity: Activity, onFinished: () -> Unit) = onFinished()

    fun showSplash(activity: Activity, onFinished: () -> Unit) = onFinished()

    fun closeTemporarySplashRoute(context: Context, onClosed: () -> Unit = {}) = onClosed()

    fun onTemporarySplashRouteClosed(context: Context) = Unit

    fun onVpnConnectionChanged(context: Context, connected: Boolean) = Unit
}

/** Ad-free layout intentionally reserves no banner space. */
@Composable
fun SorenBannerAd(settings: SorenAdsSettings, modifier: Modifier = Modifier) = Unit
