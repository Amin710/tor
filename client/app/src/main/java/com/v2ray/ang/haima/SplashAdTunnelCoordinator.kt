package com.v2ray.ang.haima

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.Utils
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cold-start splash gate that routes only AdMob traffic through a short-lived proxy-only core.
 *
 * It never requests VPN consent: [VpnService.prepare] is used only to verify that consent was
 * granted by a previous, user-initiated connection. It also never changes the normal selected
 * server, never establishes a TUN, and always waits for the native core to stop before releasing
 * the main-screen gate.
 */
internal object SplashAdTunnelCoordinator {
    private const val TAG = "TornadoSplashAd"
    private const val INITIAL_SERVICE_STATE_TIMEOUT_MS = 1_200L
    private const val START_TIMEOUT_MS = 10_000L
    private const val STOP_TIMEOUT_MS = 6_000L
    private const val PORT_SHUTDOWN_TIMEOUT_MS = 4_000L
    private const val AD_ROUTE_AND_CALLBACK_ALLOWANCE_MS = 8_000L
    private const val MIN_OVERALL_TIMEOUT_MS = 52_000L
    // Android shortService has a hard ~3 minute budget; keep a teardown margin after the
    // independent full-screen callback watchdog.
    private const val MAX_OVERALL_TIMEOUT_MS = 165_000L
    private const val MAX_CONNECT_ATTEMPTS = 3
    private const val ATTEMPT_PREFIX = "splash-ad-"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configurationCounter = AtomicLong(0L)
    private val _state = MutableStateFlow<SplashAdTunnelState>(
        SplashAdTunnelState.AwaitingBootstrap
    )
    val state: StateFlow<SplashAdTunnelState> = _state.asStateFlow()

    @Volatile
    var isTemporaryTunnelActive: Boolean = false
        private set

    @Volatile
    private var configuration: Configuration? = null
    private var completedConfiguration = -1L
    private var sessionJob: Job? = null

    fun clearConfiguration() {
        if (isTemporaryTunnelActive) return
        configuration = null
        completedConfiguration = -1L
        _state.value = SplashAdTunnelState.AwaitingBootstrap
    }

    fun configure(payload: SorenBootstrapPayload) {
        val effectiveAds = payload.ads.forCurrentBuild()
        val next = Configuration(
            generation = configurationCounter.incrementAndGet(),
            ads = effectiveAds,
            adServers = payload.adServers
        )
        configuration = next
        if (!BuildConfig.ADMOB_COMPILED_IN) {
            completedConfiguration = next.generation
            _state.value = SplashAdTunnelState.Complete(SplashAdTunnelOutcome.SKIPPED)
        } else {
            completedConfiguration = -1L
            _state.value = SplashAdTunnelState.Ready
        }
    }

    /** Idempotent for activity recreation and repeated StateFlow emissions. */
    fun start(activity: Activity, userVpnAlreadyRunning: Boolean) {
        val config = configuration ?: return
        if (completedConfiguration == config.generation || sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            execute(activity, config, userVpnAlreadyRunning)
        }
    }

    private suspend fun execute(
        activity: Activity,
        config: Configuration,
        userVpnAlreadyRunning: Boolean
    ) {
        val appContext = activity.applicationContext
        val store = SplashAdServerStore(appContext)
        val events = Channel<ServiceEvent>(Channel.UNLIMITED)
        val receiver = serviceReceiver(events)
        var receiverRegistered = false
        var storeSession: SplashAdServerStore.Session? = null
        var activeAttemptId: String? = null
        var temporaryAdRouteOpened = false
        var outcome = SplashAdTunnelOutcome.FAILED

        isTemporaryTunnelActive = true
        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags()
            )
            receiverRegistered = true

            val placementTimeout = config.ads.placements.splash.timeoutMs
                .toLong()
                .coerceIn(1_000L, 60_000L)
            val overallTimeout = (
                MAX_CONNECT_ATTEMPTS * (START_TIMEOUT_MS + STOP_TIMEOUT_MS) +
                    placementTimeout + SorenAds.FULL_SCREEN_CALLBACK_TIMEOUT_MS +
                    AD_ROUTE_AND_CALLBACK_ALLOWANCE_MS + 12_000L
                ).coerceIn(MIN_OVERALL_TIMEOUT_MS, MAX_OVERALL_TIMEOUT_MS)
            withTimeout(overallTimeout) {
                _state.value = SplashAdTunnelState.CheckingExistingVpn
                val running = userVpnAlreadyRunning || queryExistingCore(appContext, events)
                if (running) {
                    outcome = SplashAdTunnelOutcome.SKIPPED
                    return@withTimeout
                }

                withContext(Dispatchers.IO) { store.recoverInterruptedSession() }

                val permissionGranted = runCatching { VpnService.prepare(activity) == null }
                    .getOrDefault(false)
                val placement = config.ads.placements.splash
                when (val decision = SplashAdTunnelPolicy.decide(
                    vpnAlreadyRunning = false,
                    vpnPermissionPreviouslyGranted = permissionGranted,
                    vpnModeAvailable = SettingsManager.isVpnMode(),
                    adsEnabled = config.ads.enabled,
                    testMode = config.ads.testMode,
                    placement = placement,
                    adServers = config.adServers
                )) {
                    is SplashAdLaunchDecision.Skip -> {
                        Log.i(TAG, "Splash ad tunnel skipped (${decision.reason})")
                        outcome = SplashAdTunnelOutcome.SKIPPED
                        return@withTimeout
                    }
                    SplashAdLaunchDecision.StartTemporaryTunnel -> Unit
                }

                // Configure the placement before opening the proxy route. This call is idempotent
                // with MainActivity's normal settings collector.
                SorenAds.configure(activity, config.ads)
                _state.value = SplashAdTunnelState.ImportingServers
                storeSession = withContext(Dispatchers.IO) { store.prepare(config.adServers) }
                val candidates = requireNotNull(storeSession).candidateGuids
                    .take(MAX_CONNECT_ATTEMPTS)
                if (candidates.isEmpty()) {
                    outcome = SplashAdTunnelOutcome.NO_REACHABLE_AD_SERVER
                    return@withTimeout
                }

                var connected = false
                candidates.forEachIndexed { index, guid ->
                    if (connected) return@forEachIndexed
                    drainEvents(events)
                    val attemptId = ATTEMPT_PREFIX + UUID.randomUUID().toString()
                    activeAttemptId = attemptId
                    _state.value = SplashAdTunnelState.Connecting(index + 1, candidates.size)
                    if ((activity as? LifecycleOwner)?.lifecycle?.currentState
                            ?.isAtLeast(Lifecycle.State.RESUMED) != true ||
                        activity.isFinishing || activity.isDestroyed
                    ) {
                        activeAttemptId = null
                        return@forEachIndexed
                    }
                    if (!LauncherManager.startTransientService(appContext, guid, attemptId)) {
                        activeAttemptId = null
                        return@forEachIndexed
                    }

                    connected = awaitStart(events, attemptId)
                    if (!connected) {
                        stopTransientAndAwait(appContext, events, attemptId)
                        activeAttemptId = null
                    }
                }
                if (!connected) {
                    outcome = SplashAdTunnelOutcome.NO_REACHABLE_AD_SERVER
                    return@withTimeout
                }

                _state.value = SplashAdTunnelState.LoadingAd
                temporaryAdRouteOpened = true
                awaitSplashAd(activity, placement.timeoutMs.toLong())
                outcome = SplashAdTunnelOutcome.AD_FINISHED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            outcome = SplashAdTunnelOutcome.TIMED_OUT
            Log.w(TAG, "Splash ad flow reached the overall timeout")
        } catch (error: Throwable) {
            outcome = SplashAdTunnelOutcome.FAILED
            Log.e(TAG, "Splash ad flow failed open", error)
        } finally {
            withContext(NonCancellable) {
                _state.value = SplashAdTunnelState.StoppingTunnel
                if (temporaryAdRouteOpened) {
                    runCatching { awaitTemporaryRouteClear(appContext) }
                        .onFailure { Log.w(TAG, "Could not clear temporary ad route", it) }
                }
                activeAttemptId?.let { attemptId ->
                    runCatching { stopTransientAndAwait(appContext, events, attemptId) }
                        .onFailure { Log.w(TAG, "Could not stop temporary ad core cleanly", it) }
                }
                withContext(Dispatchers.IO) {
                    runCatching { store.restoreAndCleanup(storeSession) }
                        .onFailure { Log.e(TAG, "Could not restore selected server", it) }
                }
                if (receiverRegistered) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }
                events.close()
                isTemporaryTunnelActive = false
                completedConfiguration = config.generation
                _state.value = SplashAdTunnelState.Complete(outcome)
                Log.i(TAG, "Splash ad gate completed ($outcome)")
                // Re-query after releasing the suppression marker so MainViewModel receives the
                // true user-VPN state when this run was skipped for an existing connection.
                MessageHelper.sendMsg2Service(appContext, AppConfig.MSG_REGISTER_CLIENT, "")
            }
        }
    }

    private suspend fun queryExistingCore(
        context: Context,
        events: Channel<ServiceEvent>
    ): Boolean {
        drainEvents(events)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_REGISTER_CLIENT, "")
        val state = withTimeoutOrNull(INITIAL_SERVICE_STATE_TIMEOUT_MS) {
            receiveMatching(events) {
                it is ServiceEvent.Running || it is ServiceEvent.NotRunning
            }
        } ?: return false // No daemon receiver means no running core.

        if (state is ServiceEvent.Running && state.attemptId.startsWith(ATTEMPT_PREFIX)) {
            // Recover a proxy left alive after the UI process was killed.
            LauncherManager.stopTransientService(context)
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                receiveMatching(events) {
                    it is ServiceEvent.Stopped && it.attemptId == state.attemptId
                }
            }
            awaitLocalProxyShutdown()
            return false
        }
        return state is ServiceEvent.Running
    }

    private suspend fun awaitStart(events: Channel<ServiceEvent>, attemptId: String): Boolean =
        withTimeoutOrNull(START_TIMEOUT_MS) {
            when (receiveMatching(events) { event ->
                (event is ServiceEvent.Started && event.attemptId == attemptId) ||
                    event is ServiceEvent.StartFailed
            }) {
                is ServiceEvent.Started -> true
                else -> false
            }
        } ?: false

    private suspend fun stopTransientAndAwait(
        context: Context,
        events: Channel<ServiceEvent>,
        attemptId: String
    ) {
        _state.value = SplashAdTunnelState.StoppingTunnel
        LauncherManager.stopTransientService(context)
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            receiveMatching(events) {
                it is ServiceEvent.Stopped && it.attemptId == attemptId
            }
        }
        // Native stop is now synchronous before STOP_SUCCESS. The port check is a second barrier
        // for OEM/process teardown oddities and prevents a fast main-connect port collision.
        awaitLocalProxyShutdown()
    }

    private suspend fun awaitSplashAd(activity: Activity, placementTimeoutMs: Long) {
        val finished = CompletableDeferred<Unit>()
        SorenAds.showSplashThroughTemporaryRoute(activity) {
            finished.complete(Unit)
        }
        val timeout = placementTimeoutMs.coerceIn(1_000L, 60_000L)
        // Loading and on-screen display have independent deadlines. Once shown, the earlier load
        // timeout must not tear the proxy down underneath a visible/clicked advertisement.
        withTimeoutOrNull(
            timeout + SorenAds.FULL_SCREEN_CALLBACK_TIMEOUT_MS +
                AD_ROUTE_AND_CALLBACK_ALLOWANCE_MS
        ) {
            finished.await()
        }
    }

    private suspend fun awaitTemporaryRouteClear(context: Context) {
        val cleared = CompletableDeferred<Unit>()
        SorenAds.closeTemporarySplashRoute(context) { cleared.complete(Unit) }
        withTimeoutOrNull(PORT_SHUTDOWN_TIMEOUT_MS) { cleared.await() }
    }

    private suspend fun awaitLocalProxyShutdown() = withContext(Dispatchers.IO) {
        val ports = setOf(SettingsManager.getSocksPort(), SettingsManager.getHttpPort())
            .filter { it in 1..65535 }
        val deadline = System.currentTimeMillis() + PORT_SHUTDOWN_TIMEOUT_MS
        var consecutiveClosedChecks = 0
        while (System.currentTimeMillis() < deadline && consecutiveClosedChecks < 2) {
            val anyOpen = ports.any(::isLoopbackPortOpen)
            consecutiveClosedChecks = if (anyOpen) 0 else consecutiveClosedChecks + 1
            if (consecutiveClosedChecks < 2) delay(120L)
        }
    }

    private fun isLoopbackPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 120)
        }
        true
    }.getOrDefault(false)

    private fun serviceReceiver(events: Channel<ServiceEvent>) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            val content = safeIntent.getStringExtra("content").orEmpty()
            val event = when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> ServiceEvent.Running(content)
                AppConfig.MSG_STATE_NOT_RUNNING -> ServiceEvent.NotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> ServiceEvent.Started(content)
                AppConfig.MSG_STATE_START_FAILURE -> ServiceEvent.StartFailed(content)
                AppConfig.MSG_STATE_STOP_SUCCESS -> ServiceEvent.Stopped(content)
                else -> null
            }
            event?.let(events::trySend)
        }
    }

    private suspend fun receiveMatching(
        events: Channel<ServiceEvent>,
        predicate: (ServiceEvent) -> Boolean
    ): ServiceEvent {
        while (true) {
            val event = events.receive()
            if (predicate(event)) return event
        }
    }

    private fun drainEvents(events: Channel<ServiceEvent>) {
        while (events.tryReceive().isSuccess) {
            // Drain stale daemon events before correlating the next attempt.
        }
    }

    private data class Configuration(
        val generation: Long,
        val ads: SorenAdsSettings,
        val adServers: List<SorenServer>
    )

    private sealed interface ServiceEvent {
        data class Running(val attemptId: String) : ServiceEvent
        data object NotRunning : ServiceEvent
        data class Started(val attemptId: String) : ServiceEvent
        data class StartFailed(val detail: String) : ServiceEvent
        data class Stopped(val attemptId: String) : ServiceEvent
    }
}
