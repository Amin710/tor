package com.v2ray.ang.haima

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager

/**
 * Imports splash-only profiles into an unlisted group and restores the user's selection after use.
 *
 * The group deliberately has no [com.v2ray.ang.dto.entities.SubscriptionItem], so it is never
 * returned by the normal group/server UI. A tiny recovery record also repairs the selection if
 * Android kills the main process while the temporary tunnel is active.
 */
internal class SplashAdServerStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        RECOVERY_PREFERENCES,
        Context.MODE_PRIVATE
    )

    data class Session(
        val originalSelectedGuid: String?,
        val candidateGuids: List<String>
    )

    fun prepare(servers: List<SorenServer>): Session {
        recoverInterruptedSession()
        val originalGuid = MmkvManager.getSelectServer()
            ?.takeIf { MmkvManager.decodeServerConfig(it) != null }
            ?: MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
                .firstOrNull { MmkvManager.decodeServerConfig(it) != null }
        // Importing into an empty profile store may otherwise auto-select the first temp profile.
        originalGuid?.let(MmkvManager::setSelectServer)

        preferences.edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putString(KEY_ORIGINAL_GUID, originalGuid)
            .commit()

        removeTemporaryProfiles()
        val candidates = mutableListOf<String>()
        servers.asSequence()
            .filter { it.enabled && it.config.isNotBlank() }
            .distinctBy { it.id.ifBlank { it.config } }
            .sortedBy { it.priority }
            .take(MAX_SPLASH_SERVERS)
            .forEach { server ->
                val before = MmkvManager.decodeServerList(GROUP_ID).toSet()
                val (imported, _) = AngConfigManager.importBatchConfig(
                    server = server.config,
                    subid = GROUP_ID,
                    append = true
                )
                if (imported > 0) {
                    val importedGuid = MmkvManager.decodeServerList(GROUP_ID)
                        .firstOrNull { it !in before && MmkvManager.decodeServerConfig(it) != null }
                    if (importedGuid != null) candidates += importedGuid
                }
            }

        return Session(originalGuid, candidates)
    }

    fun candidateAt(session: Session, index: Int): String? {
        val guid = session.candidateGuids.getOrNull(index) ?: return null
        if (MmkvManager.decodeServerConfig(guid) == null) return null
        return guid
    }

    /** Always safe to call, including after a partial import or a process-recovery path. */
    fun restoreAndCleanup(session: Session?) {
        // A normal skip (first launch, an already-running user VPN, disabled ads, and so on)
        // never opened a store session. In that case only repair a genuinely interrupted prior
        // session; do not silently replace a valid user selection with the first default server.
        if (session == null) {
            recoverInterruptedSession()
            return
        }

        val temporaryGuids = MmkvManager.decodeServerList(GROUP_ID).toSet()
        val original = session.originalSelectedGuid
            ?: preferences.getString(KEY_ORIGINAL_GUID, null)
        val current = MmkvManager.getSelectServer()
            ?.takeIf { it !in temporaryGuids && MmkvManager.decodeServerConfig(it) != null }
        val fallback = original
            ?.takeIf { MmkvManager.decodeServerConfig(it) != null }
            ?: current
            ?: MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
                .firstOrNull {
                    it !in temporaryGuids && MmkvManager.decodeServerConfig(it) != null
                }

        fallback?.let(MmkvManager::setSelectServer)
        removeTemporaryProfiles()
        preferences.edit().clear().commit()
    }

    fun recoverInterruptedSession() {
        val staleGuids = MmkvManager.decodeServerList(GROUP_ID).toSet()
        val wasInterrupted = preferences.getBoolean(KEY_SESSION_ACTIVE, false)
        if (!wasInterrupted && staleGuids.isEmpty()) return

        val current = MmkvManager.getSelectServer()
        val original = preferences.getString(KEY_ORIGINAL_GUID, null)
            ?.takeIf { MmkvManager.decodeServerConfig(it) != null }
        val fallback = original ?: MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
            .firstOrNull { MmkvManager.decodeServerConfig(it) != null }
        if (current == null || current in staleGuids || MmkvManager.decodeServerConfig(current) == null) {
            fallback?.let(MmkvManager::setSelectServer)
        }
        removeTemporaryProfiles()
        preferences.edit().clear().commit()
    }

    private fun removeTemporaryProfiles() {
        val guids = MmkvManager.decodeServerList(GROUP_ID).toList()
        MmkvManager.removeServers(guids, GROUP_ID)
        MmkvManager.encodeServerList(mutableListOf(), GROUP_ID)
    }

    companion object {
        /** Not registered as a subscription: this is what keeps the group out of the UI. */
        internal const val GROUP_ID = "__tornado_splash_ad_servers__"
        private const val RECOVERY_PREFERENCES = "tornado_splash_ad_tunnel"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_ORIGINAL_GUID = "original_guid"
        private const val MAX_SPLASH_SERVERS = 10
    }
}
