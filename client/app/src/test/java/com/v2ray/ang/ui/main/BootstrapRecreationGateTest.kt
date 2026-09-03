package com.v2ray.ang.ui.main

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapRecreationGateTest {
    @Test
    fun activityRecreationUsesIdempotentBootstrapEntryPoint() {
        val activity = File("src/main/java/com/v2ray/ang/ui/main/MainActivity.kt").readText()
        val viewModel = File("src/main/java/com/v2ray/ang/ui/main/MainViewModel.kt").readText()

        assertTrue(activity.contains("mainViewModel.initializeIfNeeded()"))
        assertFalse(activity.contains("mainViewModel.onAction(MainAction.Initialize)"))
        assertTrue(viewModel.contains("fun initializeIfNeeded()"))
        assertTrue(viewModel.contains("if (bootstrapInitializationStarted) return"))
    }
}
