package com.v2ray.ang.haima

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SorenImageR8ContractTest {
    @Test
    fun everySignedImageGsonDtoIsKeptAndLegacySecurityDtosAreGone() {
        val rules = File("proguard-rules.pro").readText()
        listOf(
            "SorenServer",
            "SorenAdsSettings",
            "SorenAdPlacement",
            "SorenAdPlacements",
            "SorenAppSettings",
            "SorenUpdatePolicy",
            "SorenBootstrapPayload"
        ).forEach { model ->
            assertTrue(
                "Missing R8 keep rule for $model",
                rules.contains("-keep class com.v2ray.ang.haima.$model { *; }")
            )
        }
        listOf(
            "BootstrapSessionRequest",
            "BootstrapSessionResponse",
            "SecureBootstrapRequest",
            "SecureBootstrapEnvelope",
            "SorenBootstrapSecurity"
        ).forEach { legacyModel ->
            assertFalse("Legacy keep rule remains for $legacyModel", rules.contains(legacyModel))
        }
    }

    @Test
    fun imageClientDisablesAutomaticRetryAndCrossHostRedirects() {
        val client = File(
            "src/main/java/com/v2ray/ang/haima/SorenBootstrapClient.kt"
        ).readText()

        assertTrue(client.contains("followRedirects(false)"))
        assertTrue(client.contains("followSslRedirects(false)"))
        assertTrue(client.contains("retryOnConnectionFailure(false)"))
        assertTrue(client.contains("target.host != previous.host"))
        assertFalse(client.contains("v1/android/bootstrap"))
        assertFalse(client.contains("requestLimitedUseToken"))
        assertFalse(client.contains("FirebaseAppCheck"))
    }
}
