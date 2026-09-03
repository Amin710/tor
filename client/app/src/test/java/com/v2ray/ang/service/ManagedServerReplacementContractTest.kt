package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagedServerReplacementContractTest {
    @Test
    fun bootstrapReplacementNeverClearsTheLiveProfileStoreFirst() {
        val source = File(
            "src/main/java/com/v2ray/ang/ui/main/MainRepository.kt"
        ).readText()
        val start = source.indexOf("override suspend fun replaceManagedServers")
        val end = source.indexOf("override fun getManagedServerGuids", start)
        val method = source.substring(start, end)

        assertFalse(method.contains("removeAllServer()"))
        assertTrue(method.contains("AngConfigManager.importBatchConfig"))
        assertTrue(method.contains("MmkvManager.decodeServerConfig(it) != null"))
    }

    @Test
    fun selectedGuidRemainsStableForDaemonNetworkReloads() {
        val source = File(
            "src/main/java/com/v2ray/ang/handler/MmkvManager.kt"
        ).readText()
        val start = source.indexOf("internal fun saveServerProfiles")
        val end = source.indexOf("fun removeServer(guid", start)
        val method = source.substring(start, end)

        assertTrue(method.contains("preserveSelectedGuid"))
        assertTrue(method.contains("publishedProfiles[publishedGuid]"))
        assertTrue(method.contains("replacementServers = publishedProfiles.keys"))
    }

    @Test
    fun proxyOnlyServiceStopsBeforeAndroidShortServiceAnr() {
        val source = File(
            "src/main/java/com/v2ray/ang/service/CoreProxyOnlyService.kt"
        ).readText()

        assertTrue(source.contains("override fun onTimeout(startId: Int)"))
        assertTrue(source.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        assertTrue(source.contains("LOCAL_SHORT_SERVICE_LIMIT_MS = 170_000L"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(source.contains("CoreServiceManager.stopCoreLoop()"))
    }
}
