package com.v2ray.ang.haima

import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

internal enum class SorenAdRouteResult {
    FULL_READY,
    JAVA_ONLY,
    FAILED,
}

/** Makes a route callback exactly-once even when controller timeouts race callbacks. */
internal class OnceAdRouteResultCallback(
    private val callback: (SorenAdRouteResult) -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun complete(result: SorenAdRouteResult): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        callback(result)
        return true
    }
}

internal fun isValidLocalProxyPort(port: Int?): Boolean = port != null && port in 1..65535

/** Waits briefly for Xray's loopback HTTP inbound to accept TCP connections. */
internal fun probeLocalHttpProxyPort(
    port: Int,
    overallTimeoutMs: Int = 3_000,
    connectTimeoutMs: Int = 250,
    retryDelayMs: Int = 75,
): Boolean {
    if (!isValidLocalProxyPort(port) || overallTimeoutMs < 0 || connectTimeoutMs <= 0) return false

    val timeoutNanos = overallTimeoutMs.toLong() * 1_000_000L
    val deadline = System.nanoTime() + timeoutNanos
    do {
        val remainingNanos = max(0L, deadline - System.nanoTime())
        val remainingMs = max(1L, remainingNanos / 1_000_000L)
        val attemptTimeout = min(connectTimeoutMs.toLong(), remainingMs).toInt()
        val connected = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOCAL_PROXY_HOST, port), attemptTimeout)
                socket.isConnected
            }
        }.getOrDefault(false)
        if (connected) return true
        if (System.nanoTime() >= deadline) return false

        val sleepMs = min(max(0, retryDelayMs).toLong(), max(0L, remainingMs - 1L))
        if (sleepMs > 0L) {
            runCatching { Thread.sleep(sleepMs) }
                .onFailure { Thread.currentThread().interrupt() }
            if (Thread.currentThread().isInterrupted) return false
        }
    } while (System.nanoTime() <= deadline)

    return false
}

/** Restores the previous selector only while Tornado still owns the process-wide selector. */
internal fun restoreProxySelectorIfOwned(
    installedSelector: ProxySelector?,
    previousSelector: ProxySelector?,
    currentSelector: () -> ProxySelector? = { ProxySelector.getDefault() },
    setSelector: (ProxySelector?) -> Unit = { selector -> ProxySelector.setDefault(selector) },
): Boolean {
    if (installedSelector == null || currentSelector() !== installedSelector) return false
    setSelector(previousSelector)
    return true
}
