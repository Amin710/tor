package com.v2ray.ang.haima

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI

class TunnelProxyPolicyTest {
    @Test
    fun `routes remote HTTP and HTTPS through local core proxy`() {
        assertTrue(shouldRouteThroughTunnelProxy(URI("https://googleads.g.doubleclick.net/pagead")))
        assertTrue(shouldRouteThroughTunnelProxy(URI("http://pagead2.googlesyndication.com/")))
        assertFalse(shouldRouteThroughTunnelProxy(URI("wss://example.com/socket")))
    }

    @Test
    fun `never proxies loopback requests back into itself`() {
        assertFalse(shouldRouteThroughTunnelProxy(URI("http://127.0.0.1:10809/")))
        assertFalse(shouldRouteThroughTunnelProxy(URI("https://localhost/")))
        assertFalse(shouldRouteThroughTunnelProxy(URI("http://[::1]/")))
    }

    @Test
    fun `selector preserves delegate behavior for non proxyable URI`() {
        val direct = object : ProxySelector() {
            override fun select(uri: URI): MutableList<Proxy> = mutableListOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
        }
        val selector = TunnelProxySelector(direct, 10809)

        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.com"))[0].type())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://localhost"))[0])
    }

    @Test
    fun `route result callback completes exactly once`() {
        val delivered = mutableListOf<SorenAdRouteResult>()
        val callback = OnceAdRouteResultCallback(delivered::add)

        assertTrue(callback.complete(SorenAdRouteResult.FULL_READY))
        assertFalse(callback.complete(SorenAdRouteResult.FAILED))
        assertEquals(listOf(SorenAdRouteResult.FULL_READY), delivered)
    }

    @Test
    fun `local proxy probe rejects invalid ports and accepts listening loopback port`() {
        assertFalse(isValidLocalProxyPort(null))
        assertFalse(isValidLocalProxyPort(0))
        assertFalse(isValidLocalProxyPort(65_536))

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            assertTrue(
                probeLocalHttpProxyPort(
                    port = listener.localPort,
                    overallTimeoutMs = 250,
                    connectTimeoutMs = 100,
                    retryDelayMs = 1,
                ),
            )
        }
    }

    @Test
    fun `selector restore never overwrites an external replacement`() {
        val previous = directSelector()
        val tornado = TunnelProxySelector(previous, 10809)
        val external = directSelector()
        var writes = 0
        var writtenSelector: ProxySelector? = external

        assertFalse(
            restoreProxySelectorIfOwned(
                installedSelector = tornado,
                previousSelector = previous,
                currentSelector = { external },
                setSelector = { selector ->
                    writes += 1
                    writtenSelector = selector
                },
            ),
        )
        assertEquals(0, writes)
        assertSame(external, writtenSelector)

        assertTrue(
            restoreProxySelectorIfOwned(
                installedSelector = tornado,
                previousSelector = previous,
                currentSelector = { tornado },
                setSelector = { selector ->
                    writes += 1
                    writtenSelector = selector
                },
            ),
        )
        assertEquals(1, writes)
        assertSame(previous, writtenSelector)
    }

    private fun directSelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): MutableList<Proxy> = mutableListOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }
}
