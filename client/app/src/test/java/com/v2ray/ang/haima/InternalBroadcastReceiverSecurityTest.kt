package com.v2ray.ang.haima

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalBroadcastReceiverSecurityTest {
    @Test
    fun privateControlReceiversAreNeverExported() {
        val source = File("src/main/java/com/v2ray/ang/util/Utils.kt").readText()
        val function = source.substringAfter("fun receiverFlags(): Int")
            .substringBefore("fun isXray()")

        assertTrue(function.contains("ContextCompat.RECEIVER_NOT_EXPORTED"))
        assertFalse(function.contains("ContextCompat.RECEIVER_EXPORTED"))
    }
}
