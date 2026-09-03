package com.v2ray.ang.haima

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Routes main-process HTTP(S) clients through the core's loopback HTTP inbound while the
 * VPN is connected. The Xray process is intentionally excluded from the VPN, so its
 * outbound sockets still reach the physical network without forming a routing loop.
 */
internal class TunnelProxySelector(
    private val delegate: ProxySelector?,
    port: Int,
) : ProxySelector() {
    private val localAddress = InetSocketAddress.createUnresolved(LOCAL_PROXY_HOST, port)
    private val localProxy = Proxy(Proxy.Type.HTTP, localAddress)

    override fun select(uri: URI): MutableList<Proxy> {
        if (shouldRouteThroughTunnelProxy(uri)) {
            return mutableListOf(localProxy)
        }

        return runCatching { delegate?.select(uri)?.toMutableList() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: mutableListOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, error: IOException) {
        if (socketAddress == localAddress) return
        runCatching { delegate?.connectFailed(uri, socketAddress, error) }
    }
}

internal fun shouldRouteThroughTunnelProxy(uri: URI): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host = uri.host?.trim()?.lowercase() ?: return false
    return !isLoopbackHost(host)
}

internal fun isLoopbackHost(host: String): Boolean {
    val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    return normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        normalized == "::1" ||
        normalized == "0:0:0:0:0:0:0:1" ||
        normalized == "127.0.0.1" ||
        normalized.startsWith("127.")
}

internal const val LOCAL_PROXY_HOST = "127.0.0.1"
