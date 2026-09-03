package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TransientProxyContractTest {
    @Test
    fun splashUsesProxyOnlyServiceWithoutMutatingGlobalSelection() {
        val launcher = File("src/main/java/com/v2ray/ang/core/LauncherManager.kt").readText()
        val coordinator = File(
            "src/main/java/com/v2ray/ang/haima/SplashAdTunnelCoordinator.kt"
        ).readText()

        assertTrue(launcher.contains("Intent(appContext, CoreProxyOnlyService::class.java)"))
        assertTrue(launcher.contains("EXTRA_TRANSIENT_PROFILE_GUID"))
        assertFalse(
            coordinator.contains("MmkvManager.setSelectServer") ||
                coordinator.contains("LauncherManager.startService(")
        )
    }

    @Test
    fun nativeStopCompletesBeforeStopSuccessIsBroadcast() {
        val source = File("src/main/java/com/v2ray/ang/core/CoreServiceManager.kt").readText()
        val stopCall = source.indexOf("coreController.stopLoop()", source.indexOf("fun stopCoreLoop"))
        val acknowledgement = source.indexOf("MSG_STATE_STOP_SUCCESS", source.indexOf("fun stopCoreLoop"))

        assertTrue(stopCall >= 0)
        assertTrue(acknowledgement > stopCall)
        assertFalse(source.substring(stopCall, acknowledgement).contains("CoroutineScope"))
    }

    @Test
    fun skippedSplashNeverReplacesAValidUserSelection() {
        val source = File(
            "src/main/java/com/v2ray/ang/haima/SplashAdServerStore.kt"
        ).readText()
        val nullSessionGuard = source.indexOf("if (session == null)")
        val recoveryCall = source.indexOf("recoverInterruptedSession()", nullSessionGuard)
        val fallbackSelection = source.indexOf("fallback?.let(MmkvManager::setSelectServer)")

        assertTrue(nullSessionGuard >= 0)
        assertTrue(recoveryCall > nullSessionGuard)
        assertTrue(fallbackSelection > recoveryCall)
        assertTrue(source.contains("it !in temporaryGuids"))
    }
}
