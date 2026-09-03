package com.v2ray.ang.haima

import com.google.gson.Gson
import com.v2ray.ang.BuildConfig
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.zip.CRC32

/** Parses TCI1, checks corruption, verifies the backend signature, then validates the JSON. */
internal class SorenImageConfigVerifier(
    private val gson: Gson = Gson(),
    private val trustedKeyId: String = BuildConfig.HAIMA_SERVER_SIGNING_KEY_ID,
    trustedPublicKeyBase64: String = BuildConfig.HAIMA_SERVER_SIGNING_PUBLIC_KEY,
    trustedPublicKeyOverride: ECPublicKey? = null
) {
    private val trustedPublicKey: ECPublicKey =
        (trustedPublicKeyOverride ?: parsePublicKey(trustedPublicKeyBase64)).also { key ->
            if (key.params.curve.field.fieldSize != 256) {
                throw SorenImageConfigException("IMAGE_PUBLIC_KEY_INVALID")
            }
        }

    fun verifyAndParse(
        hidden: ByteArray,
        nowEpochSeconds: Long,
        expectedAudience: String,
        versionCode: Long
    ): SorenBootstrapPayload {
        val envelope = parseEnvelope(hidden)
        if (envelope.keyId != trustedKeyId) {
            throw SorenImageConfigException("IMAGE_KEY_ID_MISMATCH")
        }
        verifySignature(envelope)
        val payload = parsePayload(envelope.payload)
        val validated = SorenImagePayloadValidator.validate(
            payload = payload,
            nowEpochSeconds = nowEpochSeconds,
            expectedAudience = expectedAudience
        )
        enforceUpdatePolicy(validated, versionCode)
        return validated
    }

    private fun parseEnvelope(hidden: ByteArray): TciEnvelope {
        if (hidden.size !in MIN_TCI_BYTES..SorenImageSteganography.MAX_HIDDEN_BYTES) {
            throw SorenImageConfigException("TCI_SIZE_INVALID")
        }
        val expectedCrc = readUnsignedInt(hidden, hidden.size - CRC_BYTES)
        val crc = CRC32().apply { update(hidden, 0, hidden.size - CRC_BYTES) }.value
        if (crc != expectedCrc) throw SorenImageConfigException("TCI_CRC_MISMATCH")

        val cursor = Cursor(hidden, hidden.size - CRC_BYTES)
        if (!cursor.readBytes(MAGIC.size).contentEquals(MAGIC)) {
            throw SorenImageConfigException("TCI_MAGIC_INVALID")
        }
        if (cursor.readUnsignedByte() != VERSION) {
            throw SorenImageConfigException("TCI_VERSION_UNSUPPORTED")
        }
        val keyIdLength = cursor.readUnsignedByte()
        if (keyIdLength !in 1..MAX_KEY_ID_BYTES) {
            throw SorenImageConfigException("TCI_KEY_ID_INVALID")
        }
        val keyId = decodeUtf8(cursor.readBytes(keyIdLength))
        if (!KEY_ID_PATTERN.matches(keyId)) {
            throw SorenImageConfigException("TCI_KEY_ID_INVALID")
        }

        val payloadLength = cursor.readUnsignedIntChecked(MAX_JSON_BYTES, "TCI_PAYLOAD_LENGTH_INVALID")
        val payload = cursor.readBytes(payloadLength)
        val signatureLength = cursor.readUnsignedShort()
        if (signatureLength !in MIN_DER_SIGNATURE_BYTES..MAX_DER_SIGNATURE_BYTES) {
            throw SorenImageConfigException("TCI_SIGNATURE_LENGTH_INVALID")
        }
        val signature = cursor.readBytes(signatureLength)
        if (!cursor.exhausted()) throw SorenImageConfigException("TCI_TRAILING_DATA")
        return TciEnvelope(keyId, payload, signature)
    }

    private fun verifySignature(envelope: TciEnvelope) {
        val input = SIGNING_DOMAIN + envelope.keyId.toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0) + envelope.payload
        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(trustedPublicKey)
                update(input)
                verify(envelope.signature)
            }
        } catch (_: Exception) {
            false
        }
        if (!valid) throw SorenImageConfigException("TCI_SIGNATURE_INVALID")
    }

    private fun parsePayload(payloadBytes: ByteArray): SorenBootstrapPayload {
        val json = decodeUtf8(payloadBytes)
        return try {
            gson.fromJson(json, SorenBootstrapPayload::class.java)
                ?: throw SorenImageConfigException("IMAGE_PAYLOAD_EMPTY")
        } catch (error: SorenImageConfigException) {
            throw error
        } catch (_: Exception) {
            throw SorenImageConfigException("IMAGE_PAYLOAD_JSON_INVALID")
        }
    }

    private fun enforceUpdatePolicy(payload: SorenBootstrapPayload, versionCode: Long) {
        val policy = payload.updatePolicy
        val legacyMinimum = payload.app.forceUpdateMinVersionCode.coerceAtLeast(0)
        val policyMismatch = policy.enabled && policy.force && (
            versionCode < policy.minVersionCode.coerceAtLeast(0) ||
                (policy.maxVersionCode > 0 && versionCode > policy.maxVersionCode)
            )
        if (versionCode < legacyMinimum || policyMismatch) {
            throw SorenUpdateRequiredException(policy.copy(enabled = true, force = true))
        }
    }

    private fun parsePublicKey(encoded: String): ECPublicKey {
        val key = try {
            val der = Base64.decode(encoded.trim(), Base64.DEFAULT)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as? ECPublicKey
        } catch (_: Exception) {
            null
        } ?: throw SorenImageConfigException("IMAGE_PUBLIC_KEY_INVALID")
        if (key.params.curve.field.fieldSize != 256) {
            throw SorenImageConfigException("IMAGE_PUBLIC_KEY_INVALID")
        }
        return key
    }

    private fun decodeUtf8(value: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    } catch (_: Exception) {
        throw SorenImageConfigException("TCI_UTF8_INVALID")
    }

    private fun readUnsignedInt(value: ByteArray, offset: Int): Long =
        ((value[offset].toLong() and 0xffL) shl 24) or
            ((value[offset + 1].toLong() and 0xffL) shl 16) or
            ((value[offset + 2].toLong() and 0xffL) shl 8) or
            (value[offset + 3].toLong() and 0xffL)

    private data class TciEnvelope(
        val keyId: String,
        val payload: ByteArray,
        val signature: ByteArray
    )

    private class Cursor(private val bytes: ByteArray, private val limit: Int) {
        private var offset = 0

        fun readUnsignedByte(): Int {
            requireRemaining(1)
            return bytes[offset++].toInt() and 0xff
        }

        fun readUnsignedShort(): Int {
            requireRemaining(2)
            val value = ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)
            offset += 2
            return value
        }

        fun readUnsignedIntChecked(maximum: Int, code: String): Int {
            requireRemaining(4)
            val value = ((bytes[offset].toLong() and 0xffL) shl 24) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
                (bytes[offset + 3].toLong() and 0xffL)
            offset += 4
            if (value !in 2..maximum.toLong()) throw SorenImageConfigException(code)
            return value.toInt()
        }

        fun readBytes(count: Int): ByteArray {
            requireRemaining(count)
            return bytes.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun exhausted(): Boolean = offset == limit

        private fun requireRemaining(count: Int) {
            if (count < 0 || offset > limit - count) {
                throw SorenImageConfigException("TCI_TRUNCATED")
            }
        }
    }

    companion object {
        internal val MAGIC = byteArrayOf('T'.code.toByte(), 'C'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
        internal val SIGNING_DOMAIN = "TORNADO_IMAGE_CONFIG_V1\u0000"
            .toByteArray(StandardCharsets.US_ASCII)
        internal const val VERSION = 1
        internal const val MAX_JSON_BYTES = 36 * 1024
        private const val CRC_BYTES = 4
        private const val MIN_TCI_BYTES = 4 + 1 + 1 + 1 + 4 + 2 + 64 + CRC_BYTES
        private const val MAX_KEY_ID_BYTES = 64
        private const val MIN_DER_SIGNATURE_BYTES = 64
        private const val MAX_DER_SIGNATURE_BYTES = 80
        private val KEY_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}

internal object SorenImagePayloadValidator {
    private const val CLOCK_SKEW_SECONDS = 300L
    private const val MAX_LIFETIME_SECONDS = 30L * 24L * 60L * 60L
    private const val MAX_SERVERS = 100
    private const val MAX_AD_SERVERS = 50
    private const val MAX_CONFIG_LENGTH = 16_384
    private const val MAX_TEXT_LENGTH = 4_096
    private val CONFIG_SCHEME = Regex(
        "^(vmess|vless|trojan|ss|socks|socks4|socks5|wireguard|hysteria2|hy2|v2rayn)://"
    )

    fun validate(
        payload: SorenBootstrapPayload,
        nowEpochSeconds: Long,
        expectedAudience: String
    ): SorenBootstrapPayload {
        if (payload.schemaVersion != 1) fail("IMAGE_SCHEMA_UNSUPPORTED")
        if (payload.audiencePackageName != expectedAudience) fail("IMAGE_AUDIENCE_MISMATCH")
        if (payload.issuedAtEpochSeconds <= 0 ||
            payload.issuedAtEpochSeconds > nowEpochSeconds + CLOCK_SKEW_SECONDS
        ) fail("IMAGE_ISSUED_AT_INVALID")
        val lifetime = payload.expiresAtEpochSeconds - payload.issuedAtEpochSeconds
        if (payload.expiresAtEpochSeconds <= nowEpochSeconds ||
            lifetime !in 1..MAX_LIFETIME_SECONDS
        ) fail("IMAGE_EXPIRY_INVALID")
        if (payload.app.configRevision < 1) fail("IMAGE_REVISION_INVALID")

        val servers = validateServers(payload.servers, MAX_SERVERS, requireOne = true)
        val adServers = validateServers(payload.adServers, MAX_AD_SERVERS, requireOne = false)
        validateAds(payload.ads)
        validateApp(payload.app)
        validateUpdatePolicy(payload.updatePolicy)
        val updateIsMandatory = payload.app.forceUpdateMinVersionCode > 0 ||
            (payload.updatePolicy.enabled && payload.updatePolicy.force)
        if (updateIsMandatory && payload.updatePolicy.directUrl.isBlank() &&
            payload.updatePolicy.playStoreUrl.isBlank()
        ) fail("IMAGE_UPDATE_URL_MISSING")
        return payload.copy(servers = servers, adServers = adServers)
    }

    private fun validateServers(
        input: List<SorenServer>,
        maximum: Int,
        requireOne: Boolean
    ): List<SorenServer> {
        if (input.size > maximum) fail("IMAGE_SERVER_COUNT_INVALID")
        val enabled = input.filter(SorenServer::enabled)
        if (requireOne && enabled.isEmpty()) fail("IMAGE_NO_ACTIVE_SERVERS")
        if (enabled.size > maximum) fail("IMAGE_SERVER_COUNT_INVALID")
        val ids = HashSet<String>()
        enabled.forEach { server ->
            if (server.id.length !in 1..128 || !ids.add(server.id)) {
                fail("IMAGE_SERVER_ID_INVALID")
            }
            if (server.priority !in -1_000_000..1_000_000) fail("IMAGE_SERVER_PRIORITY_INVALID")
            val config = server.config
            if (config.length !in 1..MAX_CONFIG_LENGTH ||
                config.any { it == '\r' || it == '\n' } ||
                !CONFIG_SCHEME.containsMatchIn(config)
            ) fail("IMAGE_SERVER_CONFIG_INVALID")
        }
        return enabled.sortedBy(SorenServer::priority)
    }

    private fun validateAds(settings: SorenAdsSettings) {
        if (settings.requestTimeoutMs !in 1_000..60_000 ||
            settings.loadTimeoutMs !in 1_000..60_000 ||
            settings.interstitialEveryConnections !in 1..1_000
        ) fail("IMAGE_AD_SETTINGS_INVALID")
        listOf(settings.bannerUnitId, settings.interstitialUnitId, settings.rewardedUnitId)
            .filter(String::isNotBlank)
            .forEach { unitId ->
                if (!AD_UNIT_PATTERN.matches(unitId)) fail("IMAGE_AD_UNIT_INVALID")
            }
        listOf(
            settings.placements.beforeConnect,
            settings.placements.afterConnect,
            settings.placements.splash,
            settings.placements.appOpen
        ).forEach { placement ->
            if (placement.everyNActions !in 1..1_000 ||
                placement.cooldownSeconds !in 0..86_400 ||
                placement.timeoutMs !in 1_000..60_000 ||
                placement.maxPerDay !in 0..1_000 ||
                placement.format.lowercase() !in setOf("interstitial", "app_open", "rewarded", "banner")
            ) fail("IMAGE_AD_PLACEMENT_INVALID")
            if (placement.enabled && !AD_UNIT_PATTERN.matches(placement.unitId)) {
                fail("IMAGE_AD_UNIT_INVALID")
            }
        }
    }

    private fun validateApp(settings: SorenAppSettings) {
        listOf(
            settings.privacyPolicyUrl,
            settings.shareUrl,
            settings.supportUrl,
            settings.termsUrl,
            settings.websiteUrl
        ).forEach(::validateOptionalHttpsUrl)
        if (settings.maintenanceMessage.length > MAX_TEXT_LENGTH ||
            settings.forceUpdateMinVersionCode < 0
        ) fail("IMAGE_APP_SETTINGS_INVALID")
    }

    private fun validateUpdatePolicy(policy: SorenUpdatePolicy) {
        if (policy.minVersionCode < 0 || policy.maxVersionCode < 0 ||
            (policy.maxVersionCode > 0 && policy.maxVersionCode < policy.minVersionCode) ||
            policy.title.length > 256 || policy.message.length > MAX_TEXT_LENGTH
        ) fail("IMAGE_UPDATE_POLICY_INVALID")
        validateOptionalHttpsUrl(policy.directUrl)
        validateOptionalHttpsUrl(policy.playStoreUrl)
    }

    private fun validateOptionalHttpsUrl(value: String) {
        if (value.isBlank()) return
        val valid = try {
            val uri = java.net.URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
        } catch (_: Exception) {
            false
        }
        if (value.length > 2_048 || !valid) {
            fail("IMAGE_URL_INVALID")
        }
    }

    private fun fail(code: String): Nothing = throw SorenImageConfigException(code)

    private val AD_UNIT_PATTERN = Regex("^ca-app-pub-[0-9]{16}/[0-9]{10}$")
}
