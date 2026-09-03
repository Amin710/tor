package com.v2ray.ang.haima

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.google.gson.Gson
import com.v2ray.ang.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class SorenImageSource { PRIMARY, FALLBACK }

internal data class SorenFetchedBootstrap(
    val payload: SorenBootstrapPayload,
    val source: SorenImageSource
)

/**
 * Downloads the signed PNG carrier on every cold start. There is intentionally no disk-cache
 * fallback: an unavailable or invalid primary and fallback carrier keeps the user on Splash.
 */
class SorenBootstrapClient internal constructor(
    private val context: Context,
    gson: Gson = Gson(),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val primaryUrl: String = BuildConfig.TORNADO_CONFIG_IMAGE_PRIMARY_URL,
    private val fallbackUrl: String = BuildConfig.TORNADO_CONFIG_IMAGE_FALLBACK_URL,
    private val verifier: SorenImageConfigVerifier = SorenImageConfigVerifier(gson)
) {
    private val primary = parseConfiguredUrl(primaryUrl, PRIMARY_HOST)
    private val fallback = parseConfiguredUrl(fallbackUrl, FALLBACK_HOST)

    val isConfigured: Boolean
        get() = primary != null && fallback != null

    internal suspend fun fetch(
        onStage: (BootstrapStage) -> Unit = {}
    ): SorenFetchedBootstrap {
        check(isConfigured) { "Image bootstrap URLs are not configured" }
        val failures = mutableListOf<Throwable>()
        val sources = listOf(
            ImageAttempt(
                source = SorenImageSource.PRIMARY,
                url = requireNotNull(primary),
                downloadStage = BootstrapStage.DOWNLOADING_PRIMARY,
                decodeStage = BootstrapStage.DECODING_PRIMARY,
                verifyStage = BootstrapStage.VERIFYING_PRIMARY
            ),
            ImageAttempt(
                source = SorenImageSource.FALLBACK,
                url = requireNotNull(fallback),
                downloadStage = BootstrapStage.DOWNLOADING_FALLBACK,
                decodeStage = BootstrapStage.DECODING_FALLBACK,
                verifyStage = BootstrapStage.VERIFYING_FALLBACK
            )
        )

        for (attempt in sources) {
            try {
                val fetched = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    fetchAttempt(attempt, onStage)
                }
                if (fetched != null) return fetched
                failures += SorenImageConfigException("IMAGE_SOURCE_TIMEOUT")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (update: SorenUpdateRequiredException) {
                // An authenticated update policy is authoritative. Trying another mirror would
                // let a stale carrier bypass it.
                throw update
            } catch (error: Exception) {
                failures += error
            }
        }

        throw SorenImageConfigException("IMAGE_SOURCES_UNAVAILABLE").also { combined ->
            failures.forEach(combined::addSuppressed)
        }
    }

    private suspend fun fetchAttempt(
        attempt: ImageAttempt,
        onStage: (BootstrapStage) -> Unit
    ): SorenFetchedBootstrap {
        onStage(attempt.downloadStage)
        val png = downloadPng(cacheBusted(attempt.url))
        onStage(attempt.decodeStage)
        val hidden = try {
            decodePngCarrier(png)
        } finally {
            png.fill(0)
        }
        onStage(attempt.verifyStage)
        val payload = try {
            verifier.verifyAndParse(
                hidden = hidden,
                nowEpochSeconds = clockSeconds(),
                expectedAudience = context.packageName,
                versionCode = versionCode()
            )
        } finally {
            hidden.fill(0)
        }
        return SorenFetchedBootstrap(payload, attempt.source)
    }

    private suspend fun downloadPng(initialUrl: HttpUrl): ByteArray {
        var next = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = execute(
                Request.Builder()
                    .url(next)
                    .header("Accept", "image/png, application/octet-stream;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .get()
                    .build()
            )
            response.use {
                if (it.code in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw SorenImageConfigException("IMAGE_REDIRECT_LIMIT")
                    }
                    val location = it.header("Location")
                        ?: throw SorenImageConfigException("IMAGE_REDIRECT_INVALID")
                    next = validateRedirect(next, location)
                    return@use
                }
                if (!it.isSuccessful) {
                    throw SorenImageConfigException("IMAGE_HTTP_${it.code}")
                }
                val mediaType = it.body.contentType()
                val contentType = mediaType?.let { type ->
                    "${type.type}/${type.subtype}".lowercase()
                }
                if (contentType != "image/png" && contentType != "application/octet-stream") {
                    throw SorenImageConfigException("IMAGE_CONTENT_TYPE_INVALID")
                }
                return it.body.readLimited(MAX_PNG_BYTES).also { bytes ->
                    bytes.requirePngSignature()
                }
            }
        }
        throw SorenImageConfigException("IMAGE_REDIRECT_LIMIT")
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun decodePngCarrier(png: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth !in 1..MAX_DIMENSION || bounds.outHeight !in 1..MAX_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_PIXELS ||
            !bounds.outMimeType.equals("image/png", ignoreCase = true)
        ) {
            throw SorenImageConfigException("IMAGE_DIMENSIONS_INVALID")
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size, options)
            ?: throw SorenImageConfigException("IMAGE_DECODE_FAILED")
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
                ?: throw SorenImageConfigException("IMAGE_DECODE_FAILED")
        }
        return try {
            SorenImageSteganography.decode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun cacheBusted(url: HttpUrl): HttpUrl = cacheBusted(url, clockSeconds())

    private fun validateRedirect(previous: HttpUrl, location: String): HttpUrl {
        val target = previous.resolve(location)
            ?: throw SorenImageConfigException("IMAGE_REDIRECT_INVALID")
        if (!target.isHttps || target.host != previous.host || target.port != HTTPS_PORT ||
            target.encodedPath != CONFIG_PATH || target.username.isNotEmpty() ||
            target.password.isNotEmpty() || target.fragment != null ||
            !hasAllowedQuery(target, requireTranslate = target.host == FALLBACK_HOST) ||
            target.queryParameter(CACHE_BUSTER_QUERY) != previous.queryParameter(CACHE_BUSTER_QUERY)
        ) {
            throw SorenImageConfigException("IMAGE_REDIRECT_BLOCKED")
        }
        return target
    }

    private fun versionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun ByteArray.requirePngSignature() {
        if (size < PNG_SIGNATURE.size || !copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            throw SorenImageConfigException("IMAGE_PNG_SIGNATURE_INVALID")
        }
    }

    private fun okhttp3.ResponseBody.readLimited(maximumBytes: Int): ByteArray {
        val declared = contentLength()
        if (declared > maximumBytes) throw SorenImageConfigException("IMAGE_TOO_LARGE")
        val output = ByteArrayOutputStream(
            when {
                declared in 1L..maximumBytes.toLong() -> declared.toInt()
                else -> 32 * 1024
            }
        )
        byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maximumBytes) throw SorenImageConfigException("IMAGE_TOO_LARGE")
                output.write(buffer, 0, read)
            }
            if (declared >= 0 && total.toLong() != declared) {
                throw SorenImageConfigException("IMAGE_BODY_TRUNCATED")
            }
        }
        return output.toByteArray()
    }

    private data class ImageAttempt(
        val source: SorenImageSource,
        val url: HttpUrl,
        val downloadStage: BootstrapStage,
        val decodeStage: BootstrapStage,
        val verifyStage: BootstrapStage
    )

    companion object {
        private const val PRIMARY_HOST = "bartarindl.ir"
        private const val FALLBACK_HOST = "bartarindl-ir.translate.goog"
        private const val CONFIG_PATH = "/assets/tornado-config.png"
        private const val CACHE_BUSTER_QUERY = "b"
        private const val CACHE_BUCKET_SECONDS = 300L
        private const val HTTPS_PORT = 443
        private const val MAX_REDIRECTS = 2
        private const val MAX_PNG_BYTES = 2 * 1024 * 1024
        private const val MAX_DIMENSION = 2_048
        private const val MAX_PIXELS = 4_000_000L
        private const val SOURCE_TIMEOUT_MS = 12_000L
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )

        internal fun parseConfiguredUrl(value: String, expectedHost: String): HttpUrl? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.host != expectedHost || url.port != HTTPS_PORT ||
                url.encodedPath != CONFIG_PATH || url.username.isNotEmpty() ||
                url.password.isNotEmpty() || url.fragment != null ||
                !hasAllowedQuery(url, requireTranslate = expectedHost == FALLBACK_HOST,
                    allowCacheBuster = false)
            ) return null
            return url
        }

        internal fun cacheBusted(url: HttpUrl, epochSeconds: Long): HttpUrl = url.newBuilder()
            .setQueryParameter(
                CACHE_BUSTER_QUERY,
                (epochSeconds.coerceAtLeast(0L) / CACHE_BUCKET_SECONDS).toString()
            )
            .build()

        private fun hasAllowedQuery(
            url: HttpUrl,
            requireTranslate: Boolean,
            allowCacheBuster: Boolean = true
        ): Boolean {
            val expected = if (requireTranslate) {
                mapOf("_x_tr_sl" to "auto", "_x_tr_tl" to "en", "_x_tr_hl" to "en")
            } else {
                emptyMap()
            }
            if (expected.any { (key, value) -> url.queryParameterValues(key) != listOf(value) }) {
                return false
            }
            val permitted = expected.keys + if (allowCacheBuster) setOf(CACHE_BUSTER_QUERY) else emptySet()
            if (url.queryParameterNames.any { it !in permitted }) return false
            if (!allowCacheBuster && url.queryParameter(CACHE_BUSTER_QUERY) != null) return false
            if (allowCacheBuster && url.queryParameterValues(CACHE_BUSTER_QUERY).size != 1) return false
            return true
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
