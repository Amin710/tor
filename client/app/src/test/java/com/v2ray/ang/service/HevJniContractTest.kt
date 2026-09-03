package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HevJniContractTest {
    @Test
    fun javaDeclarationMatchesBundledNativeDescriptor() {
        val source = File("src/main/java/com/v2ray/ang/service/TProxyService.kt")
            .readText()

        assertTrue(
            source.contains(
                "private external fun TProxyStartService(configPath: String, fd: Int)"
            )
        )
        assertFalse(
            source.contains(
                "private external fun TProxyStartService(configPath: String, fd: Int): Boolean"
            )
        )

        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            val binaryText = File("libs/$abi/libhev-socks5-tunnel.so")
                .readBytes()
                .toString(Charsets.ISO_8859_1)
            assertTrue("Missing HEV start JNI symbol for $abi", binaryText.contains("TProxyStartService"))
            assertTrue(
                "HEV start JNI descriptor is not void for $abi",
                binaryText.contains("(Ljava/lang/String;I)V")
            )
            assertTrue("Missing HEV stop JNI symbol for $abi", binaryText.contains("TProxyStopService"))
            assertTrue("Missing HEV stats JNI symbol for $abi", binaryText.contains("TProxyGetStats"))
            assertTrue(
                "HEV stats JNI descriptor is missing for $abi",
                binaryText.contains("()[J")
            )
        }
    }

    @Test
    fun r8KeepsEveryHevRegisteredNativeMethod() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(
            "TProxyService must be kept explicitly because HEV registers unused methods during JNI_OnLoad",
            rules.contains("-keep class com.v2ray.ang.service.TProxyService") &&
                rules.contains("native <methods>;")
        )
    }

    @Test
    fun legacyHevLogLevelIsNormalizedForNativeConfig() {
        assertEquals("warn", normalizeHevLogLevel(null))
        assertEquals("warn", normalizeHevLogLevel("warning"))
        assertEquals("warn", normalizeHevLogLevel("WARN"))
        assertEquals("error", normalizeHevLogLevel("error"))
        assertEquals("warn", normalizeHevLogLevel("unsupported"))
    }
}
