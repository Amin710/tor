package com.v2ray.ang.haima

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SorenImageConfigVerifierTest {
    private val gson = Gson()
    private lateinit var keyPair: KeyPair
    private lateinit var verifier: SorenImageConfigVerifier

    @Before
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        verifier = SorenImageConfigVerifier(
            gson = gson,
            trustedKeyId = KEY_ID,
            trustedPublicKeyBase64 = "unused-because-a-test-key-is-injected",
            trustedPublicKeyOverride = keyPair.public as ECPublicKey
        )
    }

    @Test
    fun acceptsExactTci1EnvelopeAndReturnsValidatedSortedPayload() {
        val payload = validPayload().copy(
            servers = listOf(
                SorenServer("later", "vless://later.example:443", priority = 20),
                SorenServer("disabled", "vless://disabled.example:443", priority = 0, enabled = false),
                SorenServer("first", "vless://first.example:443", priority = 10)
            )
        )

        val verified = verifier.verifyAndParse(
            hidden = buildTci(payload),
            nowEpochSeconds = NOW,
            expectedAudience = PACKAGE_NAME,
            versionCode = VERSION_CODE
        )

        assertEquals(listOf("first", "later"), verified.servers.map(SorenServer::id))
        assertEquals(PACKAGE_NAME, verified.audiencePackageName)
        assertEquals(NOW - 60, verified.issuedAtEpochSeconds)
        assertEquals(7L, verified.app.configRevision)
    }

    @Test
    fun rejectsAnyTciByteChangedWithoutUpdatingCrc() {
        val hidden = buildTci(validPayload())
        hidden[12] = (hidden[12].toInt() xor 0x01).toByte()

        val error = assertThrows(SorenImageConfigException::class.java) {
            verify(hidden)
        }

        assertEquals("TCI_CRC_MISMATCH", error.failureCode)
    }

    @Test
    fun rejectsTamperedSignatureEvenWhenCrcIsRecomputed() {
        val hidden = buildTci(validPayload()) { signature ->
            signature.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        }

        val error = assertThrows(SorenImageConfigException::class.java) {
            verify(hidden)
        }

        assertEquals("TCI_SIGNATURE_INVALID", error.failureCode)
    }

    @Test
    fun rejectsValidlySignedPayloadForAnotherApplication() {
        val hidden = buildTci(validPayload().copy(audiencePackageName = "com.example.other"))

        val error = assertThrows(SorenImageConfigException::class.java) {
            verify(hidden)
        }

        assertEquals("IMAGE_AUDIENCE_MISMATCH", error.failureCode)
    }

    @Test
    fun rejectsExpiredValidlySignedPayload() {
        val hidden = buildTci(
            validPayload().copy(
                issuedAtEpochSeconds = NOW - 3_600,
                expiresAtEpochSeconds = NOW - 1
            )
        )

        val error = assertThrows(SorenImageConfigException::class.java) {
            verify(hidden)
        }

        assertEquals("IMAGE_EXPIRY_INVALID", error.failureCode)
    }

    @Test
    fun enforcesSignedMandatoryUpdatePolicyLocally() {
        val policy = SorenUpdatePolicy(
            enabled = true,
            force = true,
            minVersionCode = VERSION_CODE + 1,
            maxVersionCode = VERSION_CODE + 100,
            title = "Update required",
            message = "Install the current release",
            playStoreUrl = "https://play.google.com/store/apps/details?id=$PACKAGE_NAME"
        )
        val hidden = buildTci(validPayload().copy(updatePolicy = policy))

        val error = assertThrows(SorenUpdateRequiredException::class.java) {
            verify(hidden)
        }

        assertTrue(error.policy.enabled)
        assertTrue(error.policy.force)
        assertEquals(VERSION_CODE + 1, error.policy.minVersionCode)
    }

    @Test
    fun rejectsMandatoryUpdateWithoutASafeDownloadDestination() {
        val hidden = buildTci(
            validPayload().copy(
                updatePolicy = SorenUpdatePolicy(
                    enabled = true,
                    force = true,
                    minVersionCode = VERSION_CODE + 1
                )
            )
        )

        val error = assertThrows(SorenImageConfigException::class.java) {
            verify(hidden)
        }

        assertEquals("IMAGE_UPDATE_URL_MISSING", error.failureCode)
    }

    @Test
    fun rejectsAProtocolThatTheAndroidImporterCannotCreate() {
        val hidden = buildTci(
            validPayload().copy(
                servers = listOf(SorenServer("unsupported", "tuic://example.com:443"))
            )
        )

        val error = assertThrows(SorenImageConfigException::class.java) {
            verify(hidden)
        }

        assertEquals("IMAGE_SERVER_CONFIG_INVALID", error.failureCode)
    }

    private fun verify(hidden: ByteArray): SorenBootstrapPayload = verifier.verifyAndParse(
        hidden = hidden,
        nowEpochSeconds = NOW,
        expectedAudience = PACKAGE_NAME,
        versionCode = VERSION_CODE
    )

    private fun validPayload() = SorenBootstrapPayload(
        schemaVersion = 1,
        audiencePackageName = PACKAGE_NAME,
        issuedAtEpochSeconds = NOW - 60,
        expiresAtEpochSeconds = NOW + 3_600,
        servers = listOf(SorenServer("main", "vless://example.com:443", priority = 1)),
        ads = SorenAdsSettings(),
        app = SorenAppSettings(
            privacyPolicyUrl = "https://bartarindl.ir/privacy",
            shareUrl = "https://play.google.com/store/apps/details?id=$PACKAGE_NAME",
            configRevision = 7
        ),
        updatePolicy = SorenUpdatePolicy()
    )

    private fun buildTci(
        payload: SorenBootstrapPayload,
        transformSignature: (ByteArray) -> ByteArray = { it }
    ): ByteArray {
        val keyId = KEY_ID.toByteArray(StandardCharsets.US_ASCII)
        val json = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val signingInput = SorenImageConfigVerifier.SIGNING_DOMAIN + keyId + byteArrayOf(0) + json
        val signature = transformSignature(sign(signingInput, keyPair.private))

        val body = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(SorenImageConfigVerifier.MAGIC)
                output.writeByte(SorenImageConfigVerifier.VERSION)
                output.writeByte(keyId.size)
                output.write(keyId)
                output.writeInt(json.size)
                output.write(json)
                output.writeShort(signature.size)
                output.write(signature)
            }
        }.toByteArray()
        val crc = CRC32().apply { update(body) }.value
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(body)
                output.writeInt(crc.toInt())
            }
        }.toByteArray()
    }

    private fun sign(value: ByteArray, privateKey: PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(value)
            sign()
        }

    private companion object {
        const val KEY_ID = "test-signing-2026-01"
        const val PACKAGE_NAME = "com.vpn.tornadovpn"
        const val NOW = 1_800_000_000L
        const val VERSION_CODE = 1_000_021L
    }
}
