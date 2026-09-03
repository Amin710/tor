package com.v2ray.ang.haima

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.v2ray.ang.handler.SettingsManager
import java.net.ProxySelector
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Gives embedded SDK traffic a safe application-level path into the already-running VPN.
 *
 * All state and all WebView controller submissions are serialized on the main thread. WebView
 * clear/set operations are additionally queued: a new set is never submitted ahead of an older
 * clear. If Android reports a controller callback after our timeout, the desired route is
 * reconciled again so a stale clear cannot permanently remove a newer route.
 */
internal object SorenAdTrafficProxy {
    private const val TAG = "TornadoAds"
    private const val WEBVIEW_PROXY_OPERATION_TIMEOUT_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val probeExecutor = Executors.newSingleThreadExecutor { command ->
        Thread(command, "tornado-ad-proxy-probe").apply { isDaemon = true }
    }

    private data class RouteAttempt(
        val generation: Long,
        val port: Int,
        val callbacks: MutableList<OnceAdRouteResultCallback>,
    )

    private enum class WebViewAction { SET, CLEAR }

    private data class WebViewOperation(
        val id: Long,
        val generation: Long,
        val action: WebViewAction,
        val port: Int?,
        val completion: (Boolean) -> Unit,
        var completionDelivered: Boolean = false,
        var timeout: Runnable? = null,
    )

    private var generation = 0L
    private var desiredPort: Int? = null
    private var routeAttempt: RouteAttempt? = null
    private var readyPort: Int? = null
    private var readyResult: SorenAdRouteResult? = null

    private var javaReadyPort: Int? = null
    private var previousSelector: ProxySelector? = null
    private var installedSelector: TunnelProxySelector? = null

    private val webViewQueue = ArrayDeque<WebViewOperation>()
    private var webViewInFlight: WebViewOperation? = null
    private var nextWebViewOperationId = 0L
    private var webViewMayBeConfigured = false
    private var webViewReadyPort: Int? = null

    /**
     * Compatibility API. The callback still runs exactly once, including failure/fail-open paths.
     * New callers that need readiness detail should use [setVpnConnectedWithResult].
     */
    fun setVpnConnected(context: Context, connected: Boolean, onReady: (() -> Unit)? = null) {
        val completion = onReady?.let { callback ->
            OnceAdRouteResultCallback {
                runCatching(callback).onFailure { error ->
                    Log.w(TAG, "VPN ad route callback failed", error)
                }
            }
        }
        dispatchRouteRequest(context, connected, completion)
    }

    /**
     * Result-aware API used by ad gates. `FAILED` is fail-open. `JAVA_ONLY` may load SDK traffic,
     * but callers must not assume that WebView creatives are fully routed.
     */
    fun setVpnConnectedWithResult(
        context: Context,
        connected: Boolean,
        onResult: (SorenAdRouteResult) -> Unit,
    ) {
        val completion = OnceAdRouteResultCallback { result ->
            runCatching { onResult(result) }.onFailure { error ->
                Log.w(TAG, "VPN ad route result callback failed", error)
            }
        }
        dispatchRouteRequest(context, connected, completion)
    }

    /** Completes after the serialized WebView clear succeeds, fails, or reaches its timeout. */
    fun clearVpnRoute(context: Context, onClosed: (() -> Unit)? = null) {
        setVpnConnected(context, connected = false, onReady = onClosed)
    }

    private fun dispatchRouteRequest(
        @Suppress("UNUSED_PARAMETER") context: Context,
        connected: Boolean,
        completion: OnceAdRouteResultCallback?,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleRouteRequest(connected, completion) }
        } else {
            handleRouteRequest(connected, completion)
        }
    }

    private fun handleRouteRequest(
        connected: Boolean,
        completion: OnceAdRouteResultCallback?,
    ) {
        if (!connected) {
            beginDisable(completion)
            return
        }

        val port = SettingsManager.getHttpPort()
        if (!isValidLocalProxyPort(port)) {
            Log.e(TAG, "Cannot route ads through VPN: invalid local HTTP proxy port $port")
            invalidateDesiredRoute()
            requestWebViewClearIfNeeded(generation)
            completeCallback(completion, SorenAdRouteResult.FAILED)
            return
        }

        val pending = routeAttempt
        if (desiredPort == port && pending?.port == port) {
            completion?.let(pending.callbacks::add)
            return
        }

        if (desiredPort == port && readyPort == port) {
            completeCallback(completion, readyResult ?: SorenAdRouteResult.JAVA_ONLY)
            return
        }

        failPendingRouteAttempt()
        generation += 1L
        val requestGeneration = generation
        desiredPort = port
        readyPort = null
        readyResult = null
        restoreProcessProxy()
        routeAttempt = RouteAttempt(
            generation = requestGeneration,
            port = port,
            callbacks = mutableListOf<OnceAdRouteResultCallback>().apply {
                completion?.let(::add)
            },
        )

        runCatching {
            probeExecutor.execute {
                val portReady = probeLocalHttpProxyPort(port)
                mainHandler.post { handleLocalProxyProbe(requestGeneration, port, portReady) }
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not schedule local HTTP proxy probe", error)
            handleLocalProxyProbe(requestGeneration, port, ready = false)
        }
    }

    private fun handleLocalProxyProbe(requestGeneration: Long, port: Int, ready: Boolean) {
        val attempt = routeAttempt
        if (
            requestGeneration != generation ||
            desiredPort != port ||
            attempt?.generation != requestGeneration ||
            attempt.port != port
        ) {
            return
        }

        if (!ready) {
            Log.w(TAG, "Local HTTP proxy $LOCAL_PROXY_HOST:$port did not become ready")
            desiredPort = null
            readyPort = null
            readyResult = null
            completeRouteAttempt(requestGeneration, SorenAdRouteResult.FAILED)
            restoreProcessProxy()
            requestWebViewClearIfNeeded(requestGeneration)
            return
        }

        val javaInstalled = runCatching { installProcessProxy(port) }
            .onFailure { error -> Log.w(TAG, "Could not install Java HTTP VPN proxy", error) }
            .isSuccess
        if (!javaInstalled) {
            desiredPort = null
            completeRouteAttempt(requestGeneration, SorenAdRouteResult.FAILED)
            restoreProcessProxy()
            requestWebViewClearIfNeeded(requestGeneration)
            return
        }

        if (!supportsWebViewProxy()) {
            Log.w(TAG, "WebView proxy override is unavailable; Java HTTP proxy remains active")
            completeRouteAttempt(requestGeneration, SorenAdRouteResult.JAVA_ONLY)
            return
        }

        enqueueWebViewOperation(
            generation = requestGeneration,
            action = WebViewAction.SET,
            port = port,
        ) { succeeded ->
            if (requestGeneration != generation || desiredPort != port) return@enqueueWebViewOperation
            if (succeeded) {
                Log.i(TAG, "VPN ad route ready through local HTTP proxy $LOCAL_PROXY_HOST:$port")
                completeRouteAttempt(requestGeneration, SorenAdRouteResult.FULL_READY)
            } else {
                Log.w(TAG, "WebView VPN proxy unavailable; continuing with Java HTTP proxy")
                completeRouteAttempt(requestGeneration, SorenAdRouteResult.JAVA_ONLY)
            }
        }
    }

    private fun beginDisable(completion: OnceAdRouteResultCallback?) {
        generation += 1L
        desiredPort = null
        readyPort = null
        readyResult = null
        failPendingRouteAttempt()
        restoreProcessProxy()

        if (!supportsWebViewProxy() || !needsWebViewClear()) {
            completeCallback(completion, SorenAdRouteResult.FULL_READY)
            return
        }

        enqueueWebViewOperation(
            generation = generation,
            action = WebViewAction.CLEAR,
            port = null,
        ) { succeeded ->
            if (succeeded) {
                Log.i(TAG, "VPN ad route disabled")
                completeCallback(completion, SorenAdRouteResult.FULL_READY)
            } else {
                Log.w(TAG, "WebView VPN proxy clear failed or timed out")
                completeCallback(completion, SorenAdRouteResult.FAILED)
            }
        }
    }

    private fun invalidateDesiredRoute() {
        generation += 1L
        desiredPort = null
        readyPort = null
        readyResult = null
        failPendingRouteAttempt()
        restoreProcessProxy()
    }

    private fun installProcessProxy(port: Int) {
        val current = ProxySelector.getDefault()
        if (current === installedSelector && javaReadyPort == port) return

        restoreProcessProxy()
        val delegate = ProxySelector.getDefault()
        val replacement = TunnelProxySelector(delegate, port)
        ProxySelector.setDefault(replacement)
        previousSelector = delegate
        installedSelector = replacement
        javaReadyPort = port
        Log.i(TAG, "Main-process HTTP(S) traffic will use VPN proxy $LOCAL_PROXY_HOST:$port")
    }

    private fun restoreProcessProxy() {
        val installed = installedSelector
        if (installed != null) {
            val restored = runCatching {
                restoreProxySelectorIfOwned(installed, previousSelector)
            }.onFailure { error ->
                Log.w(TAG, "Could not restore system HTTP proxy selector", error)
            }.getOrDefault(false)
            if (!restored && ProxySelector.getDefault() !== installed) {
                Log.d(TAG, "ProxySelector changed externally; leaving the current selector intact")
            }
        }
        installedSelector = null
        previousSelector = null
        javaReadyPort = null
    }

    private fun enqueueWebViewOperation(
        generation: Long,
        action: WebViewAction,
        port: Int?,
        completion: (Boolean) -> Unit,
    ) {
        webViewQueue.addLast(
            WebViewOperation(
                id = ++nextWebViewOperationId,
                generation = generation,
                action = action,
                port = port,
                completion = completion,
            ),
        )
        drainWebViewQueue()
    }

    private fun drainWebViewQueue() {
        if (webViewInFlight != null) return

        while (webViewQueue.isNotEmpty()) {
            val operation = webViewQueue.removeFirst()
            if (
                operation.action == WebViewAction.SET &&
                (desiredPort != operation.port || operation.generation != generation)
            ) {
                deliverWebViewCompletion(operation, succeeded = false)
                continue
            }
            startWebViewOperation(operation)
            return
        }
    }

    private fun startWebViewOperation(operation: WebViewOperation) {
        webViewInFlight = operation
        val timeout = Runnable {
            if (webViewInFlight?.id != operation.id) return@Runnable
            Log.w(TAG, "WebView ${operation.action.name.lowercase()} operation timed out")
            finishWebViewOperation(operation, succeeded = false)
        }
        operation.timeout = timeout
        mainHandler.postDelayed(timeout, WEBVIEW_PROXY_OPERATION_TIMEOUT_MS)

        when (operation.action) {
            WebViewAction.SET -> startWebViewSet(operation)
            WebViewAction.CLEAR -> startWebViewClear(operation)
        }
    }

    private fun startWebViewSet(operation: WebViewOperation) {
        val port = operation.port
        if (!isValidLocalProxyPort(port)) {
            finishWebViewOperation(operation, succeeded = false)
            return
        }

        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("$LOCAL_PROXY_HOST:$port")
            .addBypassRule("localhost")
            .addBypassRule(LOCAL_PROXY_HOST)
            .build()
        val previousMayBeConfigured = webViewMayBeConfigured
        runCatching {
            webViewMayBeConfigured = true
            ProxyController.getInstance().setProxyOverride(proxyConfig, mainExecutor) {
                handleWebViewControllerCallback(operation)
            }
        }.onFailure { error ->
            webViewMayBeConfigured = previousMayBeConfigured
            Log.w(TAG, "Could not apply WebView VPN proxy", error)
            finishWebViewOperation(operation, succeeded = false)
        }
    }

    private fun startWebViewClear(operation: WebViewOperation) {
        runCatching {
            ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                handleWebViewControllerCallback(operation)
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not clear WebView proxy override", error)
            finishWebViewOperation(operation, succeeded = false)
        }
    }

    private fun handleWebViewControllerCallback(operation: WebViewOperation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleWebViewControllerCallback(operation) }
            return
        }

        if (webViewInFlight?.id != operation.id) {
            Log.w(TAG, "Ignoring late WebView ${operation.action.name.lowercase()} callback")
            reconcileAfterLateControllerCallback(operation)
            return
        }
        finishWebViewOperation(operation, succeeded = true)
    }

    private fun finishWebViewOperation(operation: WebViewOperation, succeeded: Boolean) {
        if (webViewInFlight?.id != operation.id) return
        operation.timeout?.let(mainHandler::removeCallbacks)
        operation.timeout = null
        webViewInFlight = null

        if (succeeded) {
            when (operation.action) {
                WebViewAction.SET -> {
                    webViewMayBeConfigured = true
                    webViewReadyPort = operation.port
                }

                WebViewAction.CLEAR -> {
                    webViewMayBeConfigured = false
                    webViewReadyPort = null
                }
            }
        }

        deliverWebViewCompletion(operation, succeeded)
        drainWebViewQueue()
    }

    private fun deliverWebViewCompletion(operation: WebViewOperation, succeeded: Boolean) {
        if (operation.completionDelivered) return
        operation.completionDelivered = true
        runCatching { operation.completion(succeeded) }.onFailure { error ->
            Log.w(TAG, "WebView proxy operation callback failed", error)
        }
    }

    /** Re-submit desired state when a timed-out controller action reports completion late. */
    private fun reconcileAfterLateControllerCallback(operation: WebViewOperation) {
        val port = desiredPort
        when {
            operation.action == WebViewAction.CLEAR && port != null -> {
                enqueueRepairSetIfMissing(port, mustRunAfterInFlight = true)
            }

            operation.action == WebViewAction.SET && port == null -> {
                enqueueRepairClearIfMissing(mustRunAfterInFlight = true)
            }

            operation.action == WebViewAction.SET && port != operation.port -> {
                enqueueRepairSetIfMissing(requireNotNull(port), mustRunAfterInFlight = true)
            }
        }
    }

    private fun enqueueRepairSetIfMissing(port: Int, mustRunAfterInFlight: Boolean = false) {
        if (
            !supportsWebViewProxy() ||
            hasQueuedWebViewOperation(WebViewAction.SET, port) ||
            (!mustRunAfterInFlight && hasInFlightWebViewOperation(WebViewAction.SET, port))
        ) {
            return
        }
        enqueueWebViewOperation(
            generation = generation,
            action = WebViewAction.SET,
            port = port,
            completion = { succeeded ->
                if (!succeeded) Log.w(TAG, "Could not repair WebView VPN proxy route")
            },
        )
    }

    private fun enqueueRepairClearIfMissing(mustRunAfterInFlight: Boolean = false) {
        if (
            !supportsWebViewProxy() ||
            hasQueuedWebViewOperation(WebViewAction.CLEAR, null) ||
            (!mustRunAfterInFlight && hasInFlightWebViewOperation(WebViewAction.CLEAR, null))
        ) {
            return
        }
        enqueueWebViewOperation(
            generation = generation,
            action = WebViewAction.CLEAR,
            port = null,
            completion = { succeeded ->
                if (!succeeded) Log.w(TAG, "Could not repair stale WebView proxy clear")
            },
        )
    }

    private fun hasInFlightWebViewOperation(action: WebViewAction, port: Int?): Boolean {
        val inFlight = webViewInFlight
        return inFlight?.action == action && inFlight.port == port
    }

    private fun hasQueuedWebViewOperation(action: WebViewAction, port: Int?): Boolean {
        return webViewQueue.any { queued -> queued.action == action && queued.port == port }
    }

    private fun requestWebViewClearIfNeeded(requestGeneration: Long) {
        if (!supportsWebViewProxy() || !needsWebViewClear()) return
        enqueueWebViewOperation(
            generation = requestGeneration,
            action = WebViewAction.CLEAR,
            port = null,
            completion = { succeeded ->
                if (!succeeded) Log.w(TAG, "Best-effort WebView proxy clear failed")
            },
        )
    }

    private fun needsWebViewClear(): Boolean {
        if (webViewMayBeConfigured || webViewReadyPort != null) return true
        if (webViewInFlight?.action == WebViewAction.SET) return true
        return webViewQueue.any { operation -> operation.action == WebViewAction.SET }
    }

    private fun completeRouteAttempt(requestGeneration: Long, result: SorenAdRouteResult) {
        val attempt = routeAttempt ?: return
        if (attempt.generation != requestGeneration) return
        routeAttempt = null
        if (result != SorenAdRouteResult.FAILED && desiredPort == attempt.port) {
            readyPort = attempt.port
            readyResult = result
        }
        attempt.callbacks.forEach { callback -> completeCallback(callback, result) }
    }

    private fun failPendingRouteAttempt() {
        val attempt = routeAttempt ?: return
        routeAttempt = null
        attempt.callbacks.forEach { callback ->
            completeCallback(callback, SorenAdRouteResult.FAILED)
        }
    }

    private fun completeCallback(
        callback: OnceAdRouteResultCallback?,
        result: SorenAdRouteResult,
    ) {
        if (callback == null) return
        runCatching { callback.complete(result) }.onFailure { error ->
            Log.w(TAG, "VPN ad route callback failed", error)
        }
    }

    private fun supportsWebViewProxy(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }.onFailure { error ->
        Log.w(TAG, "Could not query WebView proxy support", error)
    }.getOrDefault(false)
}
