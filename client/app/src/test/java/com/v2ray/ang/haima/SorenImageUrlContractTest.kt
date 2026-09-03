package com.v2ray.ang.haima

import com.v2ray.ang.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SorenImageUrlContractTest {
    @Test
    fun buildUsesExactPrimaryAndTranslateFallbackEndpoints() {
        assertEquals(PRIMARY, BuildConfig.TORNADO_CONFIG_IMAGE_PRIMARY_URL)
        assertEquals(FALLBACK, BuildConfig.TORNADO_CONFIG_IMAGE_FALLBACK_URL)

        assertNotNull(SorenBootstrapClient.parseConfiguredUrl(PRIMARY, "bartarindl.ir"))
        assertNotNull(
            SorenBootstrapClient.parseConfiguredUrl(
                FALLBACK,
                "bartarindl-ir.translate.goog"
            )
        )
    }

    @Test
    fun cacheBusterUsesFiveMinuteEpochBucketAndPreservesTranslateParameters() {
        val fallback = FALLBACK.toHttpUrl()

        val first = SorenBootstrapClient.cacheBusted(fallback, 1_800_000_299L)
        val sameBucket = SorenBootstrapClient.cacheBusted(fallback, 1_800_000_001L)
        val nextBucket = SorenBootstrapClient.cacheBusted(fallback, 1_800_000_300L)

        assertEquals("auto", first.queryParameter("_x_tr_sl"))
        assertEquals("en", first.queryParameter("_x_tr_tl"))
        assertEquals("en", first.queryParameter("_x_tr_hl"))
        assertEquals("6000000", first.queryParameter("b"))
        assertEquals(first, sameBucket)
        assertEquals("6000001", nextBucket.queryParameter("b"))
        assertEquals(
            "$FALLBACK&b=6000000",
            first.toString()
        )
    }

    @Test
    fun configuredUrlsRejectUnexpectedTransportHostPathCredentialsOrQuery() {
        listOf(
            "http://bartarindl.ir/assets/tornado-config.png",
            "https://evil.example/assets/tornado-config.png",
            "https://user:pass@bartarindl.ir/assets/tornado-config.png",
            "https://bartarindl.ir/assets/other.png",
            "$PRIMARY#fragment",
            "$PRIMARY?unexpected=1"
        ).forEach { value ->
            assertNull(value, SorenBootstrapClient.parseConfiguredUrl(value, "bartarindl.ir"))
        }

        listOf(
            "https://bartarindl-ir.translate.goog/assets/tornado-config.png",
            "$FALLBACK&_x_tr_tl=fa",
            "$FALLBACK&unexpected=1",
            "$FALLBACK&b=1"
        ).forEach { value ->
            assertNull(
                value,
                SorenBootstrapClient.parseConfiguredUrl(
                    value,
                    "bartarindl-ir.translate.goog"
                )
            )
        }
    }

    private companion object {
        const val PRIMARY = "https://bartarindl.ir/assets/tornado-config.png"
        const val FALLBACK =
            "https://bartarindl-ir.translate.goog/assets/tornado-config.png" +
                "?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en"
    }
}
