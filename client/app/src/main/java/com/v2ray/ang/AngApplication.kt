package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Tornado VPN deliberately exposes only the Play-compliant VPN mode.
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, false)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, false)
        // UI and core run in separate processes; a process-local random inbound port would make
        // the AdMob route probe a different port than the daemon actually opened.
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
        // Tornado packages the official HEV JNI tunnel for both supported ABIs.
        // Force the stable HEV path when updating over 1000010, which stored false.
        MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, true)
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "1")
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)

        // Initialize theme state from MMKV
        ThemeManager.refresh()
    }
}
