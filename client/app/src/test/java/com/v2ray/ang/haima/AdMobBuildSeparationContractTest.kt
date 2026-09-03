package com.v2ray.ang.haima

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdMobBuildSeparationContractTest {
    private val buildScript = File("build.gradle.kts").readText()
    private val noAdsManifest = File("src/main/AndroidManifest.xml").readText()
    private val adMobManifest = File("src/admob/AndroidManifest.xml").readText()

    @Test
    fun monetizedBuildRequiresARealCompileTimeAppId() {
        assertTrue(buildScript.contains("providers.gradleProperty(\"ADMOB_APP_ID\")"))
        assertTrue(buildScript.contains("admobAppIdPattern.matches(resolvedAdmobAppId)"))
        assertTrue(buildScript.contains("if (resolvedAdmobCompiledIn)"))
        assertTrue(buildScript.contains("manifestPlaceholders[\"ADMOB_APP_ID\"]"))
        assertFalse(buildScript.contains("3347511713"))
    }

    @Test
    fun monetizedBuildSelectsFullAdManifestAndServerControlsUnitIds() {
        assertTrue(buildScript.contains("manifest.srcFile(\"src/admob/AndroidManifest.xml\")"))
        assertTrue(buildScript.contains("implementation(libs.androidx.webkit)"))
        assertTrue(adMobManifest.contains("com.google.android.gms.ads.APPLICATION_ID"))
        assertTrue(adMobManifest.contains("android:value=\"\${ADMOB_APP_ID}\""))
        assertTrue(adMobManifest.contains("com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT"))
        assertTrue(adMobManifest.contains("com.google.android.gms.permission.AD_ID"))
        assertTrue(adMobManifest.contains(".service.CoreProxyOnlyService"))
        assertFalse(adMobManifest.contains("ca-app-pub-"))
        assertFalse(adMobManifest.contains("UnitId"))
    }

    @Test
    fun defaultPublishingManifestRemainsAdFree() {
        assertFalse(noAdsManifest.contains("com.google.android.gms.ads.APPLICATION_ID"))
        assertTrue(
            noAdsManifest.contains(
                "android:name=\"com.google.android.gms.permission.AD_ID\"\n        tools:node=\"remove\""
            )
        )
        assertTrue(noAdsManifest.contains("android:name=\"android.ext.adservices\""))
        assertFalse(noAdsManifest.contains(".service.CoreProxyOnlyService"))
    }
}
