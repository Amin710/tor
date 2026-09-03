package com.v2ray.ang.haima

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelsTest {
    private val gson = Gson()

    @Test
    fun parsesSignedImagePayloadWithAudienceIssueTimeAdsAndUpdatePolicy() {
        val payload = gson.fromJson(
            """
            {
              "schemaVersion": 1,
              "audiencePackageName": "com.vpn.tornadovpn",
              "issuedAtEpochSeconds": 1999996400,
              "expiresAtEpochSeconds": 2000000000,
              "servers": [{"id":"one","config":"vless://example","priority":1,"enabled":true}],
              "adServers": [{"id":"ad-one","config":"vless://ad.example","priority":2,"enabled":true}],
              "ads": {
                "enabled": true,
                "requestTimeoutMs": 7000,
                "loadTimeoutMs": 11000,
                "umpRequired": true,
                "testMode": false,
                "placements": {
                  "beforeConnect": {"enabled":true,"unitId":"before","everyNActions":2,"cooldownSeconds":30,"timeoutMs":9000,"maxPerDay":4},
                  "afterConnect": {"enabled":true,"unitId":"after","everyNActions":3,"cooldownSeconds":60,"timeoutMs":10000,"maxPerDay":5},
                  "splash": {"enabled":true,"format":"interstitial","unitId":"splash-interstitial","everyNActions":1,"cooldownSeconds":0,"timeoutMs":12000,"maxPerDay":1},
                  "appOpen": {"enabled":true,"format":"app_open","unitId":"open","everyNActions":1,"cooldownSeconds":300,"timeoutMs":12000,"maxPerDay":2}
                }
              },
              "app": {"privacyPolicyUrl":"https://example.com/privacy","configRevision":9},
              "updatePolicy": {"enabled":true,"force":true,"minVersionCode":56,"maxVersionCode":56,"title":"Update","message":"Required","directUrl":"https://example.com/app.apk","playStoreUrl":"https://play.google.com/store/apps/details?id=test"}
            }
            """.trimIndent(),
            SorenBootstrapPayload::class.java
        )

        assertEquals("com.vpn.tornadovpn", payload.audiencePackageName)
        assertEquals(1_999_996_400L, payload.issuedAtEpochSeconds)
        assertEquals(2_000_000_000L, payload.expiresAtEpochSeconds)
        assertTrue(payload.ads.placements.beforeConnect.enabled)
        assertEquals("after", payload.ads.placements.afterConnect.unitId)
        assertEquals("splash-interstitial", payload.ads.placements.splash.unitId)
        assertEquals(2, payload.ads.placements.appOpen.maxPerDay)
        assertEquals("ad-one", payload.adServers.single().id)
        assertEquals(9L, payload.app.configRevision)
        assertEquals(56L, payload.updatePolicy.minVersionCode)
    }

    @Test
    fun serializedImagePayloadHasNoLegacySecurityOrAttestationFields() {
        val json = gson.toJson(
            SorenBootstrapPayload(
                audiencePackageName = "com.vpn.tornadovpn",
                issuedAtEpochSeconds = 1_700_000_000,
                expiresAtEpochSeconds = 1_700_003_600,
                servers = listOf(SorenServer("one", "vless://example")),
                app = SorenAppSettings(configRevision = 1)
            )
        )

        assertTrue(json.contains("\"audiencePackageName\":\"com.vpn.tornadovpn\""))
        assertTrue(json.contains("\"issuedAtEpochSeconds\":1700000000"))
        assertFalse(json.contains("security", ignoreCase = true))
        assertFalse(json.contains("attestation", ignoreCase = true))
        assertFalse(json.contains("deviceCredential"))
    }
}
