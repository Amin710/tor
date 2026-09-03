package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import java.lang.ref.SoftReference

class CoreProxyOnlyService : Service(), ServiceControl {
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val hardStop = Runnable {
        LogUtil.w(AppConfig.TAG, "StartCore-Proxy: local short-service watchdog expired")
        stopImmediately()
    }
    private var ownsCore = false
    private var transientAttemptId = ""

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        // The service control is installed only after onStartCommand has proved that it will not
        // replace an already-running user VPN in the shared daemon process.
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val guid = intent?.getStringExtra(AppConfig.EXTRA_TRANSIENT_PROFILE_GUID).orEmpty()
        transientAttemptId = intent?.getStringExtra(AppConfig.EXTRA_TRANSIENT_ATTEMPT_ID).orEmpty()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: transient service command received")

        val existingControl = CoreServiceManager.serviceControl?.get()
        if (existingControl != null && existingControl !== this) {
            // Treat a user service that is still starting/stopping as active too. Waiting for
            // isRunning() alone leaves a window where the transient core can steal its control.
            LogUtil.w(AppConfig.TAG, "StartCore-Proxy: refusing to replace an active user service")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, transientAttemptId)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (guid.isBlank()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Proxy: missing transient profile")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, transientAttemptId)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        CoreServiceManager.serviceControl = SoftReference(this)
        NotificationManager.ensureForeground()
        // Own cleanup from this point even if core startup fails after registering receivers.
        ownsCore = true
        if (!CoreServiceManager.startCoreLoop(null, guid, transientAttemptId)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lifecycleHandler.removeCallbacks(hardStop)
        lifecycleHandler.postDelayed(hardStop, LOCAL_SHORT_SERVICE_LIMIT_MS)

        return START_NOT_STICKY
    }

    /** Android 14 short-service deadline callback. Stop promptly to avoid an ANR. */
    override fun onTimeout(startId: Int) {
        LogUtil.w(AppConfig.TAG, "StartCore-Proxy: Android 14 short-service timeout")
        stopImmediately(startId)
    }

    /** Android 15+ foreground-service deadline callback. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        LogUtil.w(AppConfig.TAG, "StartCore-Proxy: system short-service timeout")
        stopImmediately(startId)
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        lifecycleHandler.removeCallbacks(hardStop)
        super.onDestroy()
        if (ownsCore) {
            ownsCore = false
            CoreServiceManager.stopCoreLoop()
        }
        if (CoreServiceManager.serviceControl?.get() === this) {
            CoreServiceManager.serviceControl = null
        }
    }

    private fun stopImmediately(startId: Int? = null) {
        lifecycleHandler.removeCallbacks(hardStop)
        if (ownsCore) {
            ownsCore = false
            CoreServiceManager.stopCoreLoop()
        }
        if (CoreServiceManager.serviceControl?.get() === this) {
            CoreServiceManager.serviceControl = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        stopSelf()
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    private companion object {
        // Kept below Android's roughly three-minute shortService deadline, including teardown.
        const val LOCAL_SHORT_SERVICE_LIMIT_MS = 170_000L
    }
}
