package com.v2ray.ang.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AngApplication
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.haima.BootstrapStatus
import com.v2ray.ang.haima.SorenAds
import com.v2ray.ang.haima.SplashAdTunnelCoordinator
import com.v2ray.ang.haima.isEntryGateComplete
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : BaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
            else {
                mainViewModel.markConnectionStartCancelled()
                toastError(R.string.soren_vpn_permission_required)
            }
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Activity may be recreated for rotation/theme/locale while the ViewModel survives.
        // A recreation must not restart bootstrap and throw an already-open app back to Splash.
        mainViewModel.initializeIfNeeded()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mainViewModel.uiState.map { it.ads }.distinctUntilChanged().collect { settings ->
                        SorenAds.configure(this@MainActivity, settings)
                    }
                }
                launch {
                    mainViewModel.uiState.map(MainUiState::normalAdRouteState)
                        .distinctUntilChanged()
                        .collect { connected ->
                            // null: showAfterSuccessfulConnection exclusively owns route setup
                            // until its ad gate completes. false still always clears a stopped VPN.
                            if (connected != null) {
                                SorenAds.onVpnConnectionChanged(applicationContext, connected)
                            }
                        }
                }
                launch {
                    mainViewModel.uiState.map { state ->
                        state.successfulConnectionEventId to
                            (state.isRunning && state.status is MainStatus.Connecting)
                    }
                        .distinctUntilChanged()
                        .collect { (eventId, presentationPending) ->
                            if (eventId > 0L && presentationPending) {
                                SorenAds.showAfterSuccessfulConnection(this@MainActivity) {
                                    mainViewModel.completeConnectionPresentation(eventId)
                                }
                            }
                        }
                }
                launch {
                    mainViewModel.uiState.map { it.bootstrapStatus to it.isRunning }
                        .distinctUntilChanged()
                        .collect { (status, _) ->
                            if (status is BootstrapStatus.Ready &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            ) {
                                startSplashAdFlowIfReady()
                            }
                        }
                }
                launch {
                    SplashAdTunnelCoordinator.state
                        .map { it.isEntryGateComplete }
                        .distinctUntilChanged()
                        .collect { complete ->
                            if (complete) requestNotificationPermissionIfNeeded()
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SorenAds.onActivityResumed(this)
        startSplashAdFlowIfReady()
    }

    override fun onPause() {
        SorenAds.onActivityPaused(this)
        super.onPause()
    }

    override fun onDestroy() {
        SorenAds.onActivityDestroyed(this)
        super.onDestroy()
    }

    private fun startSplashAdFlowIfReady() {
        val state = mainViewModel.uiState.value
        if (state.bootstrapStatus is BootstrapStatus.Ready) {
            SplashAdTunnelCoordinator.start(this, userVpnAlreadyRunning = state.isRunning)
        }
    }

    /** Never let a system permission dialog pause the Activity while a full-screen ad owns it. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                if (action == MainAction.ToggleService) handleConnectAction()
                else mainViewModel.onAction(action)
            },
            onDrawerAction = ::handleDrawerAction,
            onOpenUrl = { url ->
                if (url.startsWith("https://", ignoreCase = true)) Utils.openUri(this, url)
            },
        )
    }

    private fun handleDrawerAction(action: SorenDrawerAction) {
        val settings = mainViewModel.uiState.value.appSettings
        when (action) {
            SorenDrawerAction.Privacy -> {
                val url = settings.privacyPolicyUrl
                if (url.startsWith("https://") && !url.contains(".invalid")) {
                    Utils.openUri(this, url)
                } else {
                    toastError(R.string.soren_configuration_unavailable)
                }
            }
            SorenDrawerAction.Share -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${getString(R.string.app_name)}\n${settings.shareUrl}")
                }
                startActivity(Intent.createChooser(intent, getString(R.string.soren_share)))
            }
        }
    }

    private fun handleConnectAction() {
        val state = mainViewModel.uiState.value
        // While the core is starting or the after-connect ad gate is pending, the large button
        // is disabled by Compose. Keep this guard as a second line of defence against duplicate
        // accessibility/automation clicks.
        if (state.status is MainStatus.SelectingServer || state.status is MainStatus.Connecting) {
            return
        }
        if (state.isRunning) {
            LauncherManager.stopService(this)
            return
        }

        when (state.bootstrapStatus) {
            BootstrapStatus.Ready -> Unit
            else -> {
                toastError(R.string.soren_configuration_unavailable)
                return
            }
        }

        SorenAds.showBeforeConnection(this) {
            mainViewModel.selectBestServer { selected ->
                if (selected) requestServiceStart()
                else toastError(R.string.soren_no_reachable_server)
            }
        }
    }

    private fun requestServiceStart() {
        mainViewModel.markConnectionStarting()
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        try {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent == null) startV2Ray()
            else requestVpnPermission.launch(permissionIntent)
        } catch (_: Throwable) {
            mainViewModel.markConnectionStartCancelled()
            toastError(R.string.toast_services_failure)
        }
    }

    private fun startV2Ray() {
        val guid = mainViewModel.uiState.value.selectedGuid
        if (guid.isNullOrBlank()) {
            mainViewModel.markConnectionStartCancelled()
            toastError(R.string.soren_no_reachable_server)
            return
        }
        if (!LauncherManager.startService(this, guid)) {
            mainViewModel.markConnectionStartCancelled()
            toastError(R.string.toast_services_failure)
        }
    }
}
