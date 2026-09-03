plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val tornadoConfigImagePrimaryUrl = providers.gradleProperty("TORNADO_CONFIG_IMAGE_PRIMARY_URL")
    .orElse("https://bartarindl.ir/assets/tornado-config.png")
val tornadoConfigImageFallbackUrl = providers.gradleProperty("TORNADO_CONFIG_IMAGE_FALLBACK_URL")
    .orElse(
        "https://bartarindl-ir.translate.goog/assets/tornado-config.png" +
            "?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en"
    )
val haimaSigningKeyId = providers.gradleProperty("HAIMA_SERVER_SIGNING_KEY_ID")
    .orElse("tornado-signing-2026-01")
val haimaSigningPublicKey = providers.gradleProperty("HAIMA_SERVER_SIGNING_PUBLIC_KEY")
    .orElse(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa9HOUmOljQhWAMxq/3D1965cCO57vcYvqdXEOSVX53ROpUoiZjvdAEw/2BjzEN4jwj5+3WlvAFmsxdgyVcR1ow=="
    )
val admobCompiledIn = providers.gradleProperty("TORNADO_ADMOB_COMPILED_IN")
    .orElse("false")
    .map(String::toBoolean)
val admobAppId = providers.gradleProperty("ADMOB_APP_ID")
    .map { it.trim() }
    .orElse("")
val resolvedAdmobCompiledIn = admobCompiledIn.get()
val resolvedAdmobAppId = admobAppId.get()
val admobAppIdPattern = Regex("^ca-app-pub-[0-9]{16}~[0-9]{10}$")

if (resolvedAdmobCompiledIn) {
    require(admobAppIdPattern.matches(resolvedAdmobAppId)) {
        "ADMOB_APP_ID must be a real AdMob app ID in the form " +
            "ca-app-pub-################~########## when TORNADO_ADMOB_COMPILED_IN=true"
    }
}
val enableAbiSplits = providers.gradleProperty("TORNADO_ABI_SPLITS")
    .orElse("false")
    .map(String::toBoolean)
val uploadStorePath = providers.gradleProperty("TORNADO_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.gradleProperty("TORNADO_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.gradleProperty("TORNADO_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.gradleProperty("TORNADO_UPLOAD_KEY_PASSWORD").orNull
val hasUploadKey = listOf(
    uploadStorePath,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword
).all { !it.isNullOrBlank() }

// Keep the 59 MiB AAR as deterministic binary parts for reliable repository
// transport. Gradle restores the original build input automatically and
// verifies it before compilation.
val bundledV2RayAar = layout.projectDirectory.file("libs/libv2ray.aar")
val bundledV2RayParts = fileTree("libs") {
    include("libv2ray.aar.part-*")
}
val restoreBundledV2RayAar by tasks.registering {
    inputs.files(bundledV2RayParts)
    outputs.file(bundledV2RayAar)

    doLast {
        val parts = bundledV2RayParts.files.sortedBy { it.name }
        check(parts.isNotEmpty()) { "Bundled libv2ray AAR parts are missing." }

        val output = bundledV2RayAar.asFile
        output.parentFile.mkdirs()
        output.outputStream().buffered().use { destination ->
            parts.forEach { part ->
                part.inputStream().buffered().use { source -> source.copyTo(destination) }
            }
        }

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        output.inputStream().buffered().use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha256 = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        check(actualSha256 == "670cf11d9d10a6bb6548ac4f593acfa4339155732f6f8de4d45923f30a74deed") {
            "Bundled libv2ray AAR checksum mismatch: $actualSha256"
        }
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.vpn.tornadovpn"
        minSdk = 24
        targetSdk = 37
        versionCode = 1000021
        versionName = "1000021"

        buildConfigField(
            "String",
            "TORNADO_CONFIG_IMAGE_PRIMARY_URL",
            quoted(tornadoConfigImagePrimaryUrl.get().trim())
        )
        buildConfigField(
            "String",
            "TORNADO_CONFIG_IMAGE_FALLBACK_URL",
            quoted(tornadoConfigImageFallbackUrl.get().trim())
        )
        buildConfigField(
            "String",
            "HAIMA_SERVER_SIGNING_KEY_ID",
            quoted(haimaSigningKeyId.get().trim())
        )
        buildConfigField(
            "String",
            "HAIMA_SERVER_SIGNING_PUBLIC_KEY",
            quoted(haimaSigningPublicKey.get().trim())
        )
        buildConfigField(
            "boolean",
            "ADMOB_COMPILED_IN",
            resolvedAdmobCompiledIn.toString()
        )
        if (resolvedAdmobCompiledIn) {
            // Unit IDs intentionally remain signed-image/server controlled. Only the AdMob
            // application ID has to be compiled into AndroidManifest.xml.
            manifestPlaceholders["ADMOB_APP_ID"] = resolvedAdmobAppId
        }
        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
            ?.filter(String::isNotBlank)
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk {
            abiFilters += abiFilterList
        }
        splits {
            abi {
                isEnable = enableAbiSplits.get()
                reset()
                include(*abiFilterList.toTypedArray())
                isUniversalApk = false
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("tornadoRelease") {
            if (hasUploadKey) {
                storeFile = file(requireNotNull(uploadStorePath))
                storePassword = requireNotNull(uploadStorePassword)
                keyAlias = requireNotNull(uploadKeyAlias)
                keyPassword = requireNotNull(uploadKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasUploadKey) {
                signingConfig = signingConfigs.getByName("tornadoRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    defaultConfig {
        buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
            if (resolvedAdmobCompiledIn) {
                java.srcDir("src/admob/java")
                // The publishing-safe main manifest removes every AdMob surface. Select the
                // complete ad-enabled manifest only for an explicitly monetized binary.
                manifest.srcFile("src/admob/AndroidManifest.xml")
            } else {
                // Compile the publishing-safe no-op implementation and leave the full AdMob
                // implementation out of this binary altogether.
                java.srcDir("src/noads/java")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "TornadoVPN_${variant.versionName}_${abi}.apk"
            }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

// A Play/Telegram release that was accidentally emitted unsigned cannot update either channel.
// Keep debug and IDE sync usable, but make every release artifact task fail closed unless all
// four Upload Key properties are present.
tasks.configureEach {
    if (name in setOf("packageRelease", "bundleRelease", "assembleRelease")) {
        doFirst {
            check(hasUploadKey) {
                "Release signing is not configured. Set all TORNADO_UPLOAD_* Gradle properties."
            }
        }
    }
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(files(bundledV2RayAar).builtBy(restoreBundledV2RayAar))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    // Optional server-controlled ad monetization, independent from signed-image bootstrap.
    if (resolvedAdmobCompiledIn) {
        implementation(libs.google.mobile.ads)
        implementation(libs.google.ump)
        // SorenAdTrafficProxy uses ProxyController/WebViewFeature directly. Keep this explicit
        // instead of relying on an implementation detail of the Google Ads dependency graph.
        implementation(libs.androidx.webkit)
    }

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)
    // CameraX and the legacy subscription worker expose Guava's ListenableFuture in their
    // compile-time API. Do not rely on the Ads runtime graph to provide this type.
    implementation(libs.guava)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
