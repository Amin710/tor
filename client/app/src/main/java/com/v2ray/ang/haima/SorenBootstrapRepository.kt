package com.v2ray.ang.haima

import android.content.Context
import com.google.gson.Gson
import com.v2ray.ang.ui.main.MainDataSource

class SorenBootstrapRepository(
    context: Context,
    private val dataSource: MainDataSource,
    private val gson: Gson = Gson()
) {
    private val appContext = context.applicationContext
    private val client = SorenBootstrapClient(appContext, gson)

    suspend fun refresh(onStage: (BootstrapStage) -> Unit = {}): BootstrapResult {
        SplashAdTunnelCoordinator.clearConfiguration()
        if (!client.isConfigured) {
            return BootstrapResult(BootstrapStatus.BackendSetupRequired, null, 0)
        }
        return try {
            onStage(BootstrapStage.PREPARING)
            val fetched = client.fetch(onStage)
            val payload = fetched.payload
            onStage(BootstrapStage.IMPORTING)
            val count = apply(payload)
            check(count > 0) { "Server import failed" }
            SplashAdTunnelCoordinator.configure(payload)
            BootstrapResult(BootstrapStatus.Ready, payload, count)
        } catch (update: SorenUpdateRequiredException) {
            BootstrapResult(BootstrapStatus.UpdateRequired(update.policy), null, 0, update)
        } catch (networkError: Exception) {
            BootstrapResult(
                BootstrapStatus.Error("Could not receive server settings"),
                null,
                0,
                networkError
            )
        }
    }

    private suspend fun apply(payload: SorenBootstrapPayload): Int {
        val configs = payload.servers.map { it.config }
        return dataSource.replaceManagedServers(configs)
    }

    data class BootstrapResult(
        val status: BootstrapStatus,
        val payload: SorenBootstrapPayload?,
        val importedServerCount: Int,
        val cause: Throwable? = null
    )
}
