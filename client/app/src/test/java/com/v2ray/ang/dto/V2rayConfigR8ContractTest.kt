package com.v2ray.ang.dto

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V2rayConfigR8ContractTest {
    @Test
    fun bundledTemplateParsesAndKeepsItsJsonSchema() {
        val source = File("src/main/assets/v2ray_config.json").readText()
        val config = Gson().fromJson(source, V2rayConfig::class.java)

        assertNotNull(config)
        assertFalse(config.inbounds.isEmpty())
        assertEquals("socks", config.inbounds.first().tag)
        assertEquals("socks", config.inbounds.first().protocol)
        assertFalse(config.outbounds.isEmpty())
        assertEquals("proxy", config.outbounds.first().tag)

        val roundTrip = JsonParser.parseString(Gson().toJson(config)).asJsonObject
        assertTrue(roundTrip.has("inbounds"))
        assertTrue(roundTrip.has("outbounds"))
        assertTrue(roundTrip.has("routing"))
        assertTrue(roundTrip.getAsJsonArray("inbounds").first().asJsonObject.has("protocol"))
    }

    @Test
    fun r8RulesProtectEveryGsonDtoAndGenericSignature() {
        val rules = File("proguard-rules.pro").readText()

        assertTrue(rules.contains("-keep class com.v2ray.ang.dto.** { *; }"))
        assertTrue(rules.contains("-keep class com.v2ray.ang.enums.** { *; }"))
        assertTrue(rules.contains("Signature,InnerClasses,EnclosingMethod"))
        assertTrue(rules.contains("RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault"))
    }
}
