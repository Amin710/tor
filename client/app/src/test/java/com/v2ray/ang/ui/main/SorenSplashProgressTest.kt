package com.v2ray.ang.ui.main

import com.v2ray.ang.haima.BootstrapStage
import com.v2ray.ang.haima.BootstrapStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SorenSplashProgressTest {
    @Test
    fun everyRealBootstrapAndAdStageAdvancesProgressMonotonically() {
        val stages = listOf(
            BootstrapStage.PREPARING,
            BootstrapStage.DOWNLOADING_PRIMARY,
            BootstrapStage.DECODING_PRIMARY,
            BootstrapStage.VERIFYING_PRIMARY,
            BootstrapStage.DOWNLOADING_FALLBACK,
            BootstrapStage.DECODING_FALLBACK,
            BootstrapStage.VERIFYING_FALLBACK,
            BootstrapStage.IMPORTING,
            BootstrapStage.CHECKING_AD_ROUTE,
            BootstrapStage.CONNECTING_AD_ROUTE,
            BootstrapStage.LOADING_SPLASH_AD,
            BootstrapStage.FINALIZING
        )
        val progress = stages.map { stage ->
            splashProgress(BootstrapStatus.Loading(stage))
        }

        assertEquals(stages.size, progress.distinct().size)
        assertTrue(progress.all { it > 0f && it < 1f })
        assertTrue(progress.zipWithNext().all { (before, after) -> after > before })
        assertEquals(0.06f, progress.first(), 0f)
        assertEquals(0.98f, progress.last(), 0f)
    }

    @Test
    fun onlyReadyCompletesProgressAndErrorsNeverPretendToComplete() {
        assertEquals(1f, splashProgress(BootstrapStatus.Ready), 0f)
        assertEquals(0f, splashProgress(BootstrapStatus.Error("offline")), 0f)
        assertEquals(0f, splashProgress(BootstrapStatus.BackendSetupRequired), 0f)
    }

    @Test
    fun splashUsesActualStagesInsteadOfAnIndependentTimerLoop() {
        val source = File("src/main/java/com/v2ray/ang/ui/main/SorenSplash.kt").readText()

        assertTrue(source.contains("is BootstrapStatus.Loading -> when (status.stage)"))
        assertFalse(source.contains("LaunchedEffect"))
        assertFalse(source.contains("delay("))
    }
}
