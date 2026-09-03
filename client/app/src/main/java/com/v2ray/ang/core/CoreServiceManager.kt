package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

object CoreServiceManager {

    // Creating the controller can load Go/JNI state. Defer it until serviceControl's
    // setter has initialized the native environment in the daemon process.
    private val coreController: CoreController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoreNativeManager.newCoreController(CoreCallback())
    }
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var currentProfileGuid: String? = null
    private var currentStartAttemptId: String = ""
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null

    @Volatile
    private var isReloading = false

    @Volatile
    private var nativeInitFailure: Throwable? = null

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            try {
                CoreNativeManager.initCoreEnv(service)
                if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    processFinder = XrayProcessFinder(service)
                    coreController.registerProcessFinder(processFinder)
                }
                nativeInitFailure = null
            } catch (failure: Throwable) {
                nativeInitFailure = failure
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Native initialization failed", failure)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning(): Boolean {
        if (nativeInitFailure != null) return false
        return runCatching { coreController.isRunning }.getOrDefault(false)
    }

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(
        vpnInterface: ParcelFileDescriptor?,
        guidOverride: String? = null,
        startAttemptId: String = ""
    ): Boolean {
        nativeInitFailure?.let { failure ->
            val service = getService()
            val message = failure.message?.takeUnless(String::isBlank) ?: "Native initialization failed"
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", failure)
            if (service != null) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            }
            return false
        }
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface, guidOverride, startAttemptId)
            return true
        } catch (e: Throwable) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        guidOverride: String?,
        startAttemptId: String
    ) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface, guidOverride, startAttemptId)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        guidOverride: String? = null,
        startAttemptId: String = "",
        isReload: Boolean = false
    ) {
        val guid = guidOverride ?: MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")
        currentConfig = config
        currentProfileGuid = guid
        currentStartAttemptId = startAttemptId

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        // The generated core JSON contains server addresses and credentials. Never emit it to
        // logcat, including debug builds used for release troubleshooting.
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, startAttemptId)
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null

        var nativeStoppedCleanly = true
        if (isRunning()) {
            try {
                // STOP_SUCCESS is a hand-off barrier for the splash proxy. Do not acknowledge
                // until native listeners are actually closed, otherwise a normal connection can
                // race the async stop and fail with local-port-in-use.
                coreController.stopLoop()
            } catch (e: Exception) {
                nativeStoppedCleanly = false
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        val stoppedAttemptId = currentStartAttemptId
        currentConfig = null
        currentProfileGuid = null
        currentStartAttemptId = ""
        if (nativeStoppedCleanly) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, stoppedAttemptId)
        }
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(): Boolean {
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false

        return try {
            val tunFd = currentVpnInterface

            isReloading = true
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            coreController.stopLoop()
            launchCore(
                service = service,
                vpnInterface = tunFd,
                guidOverride = currentProfileGuid,
                startAttemptId = currentStartAttemptId,
                isReload = true
            )

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (!isRunning()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
            )
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageHelper.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_RESULT,
                        result.copy(
                            country = ip.country,
                            ipAddress = ip.ipAddress,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(
                            serviceControl.getService(),
                            AppConfig.MSG_STATE_RUNNING,
                            currentStartAttemptId
                        )
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
