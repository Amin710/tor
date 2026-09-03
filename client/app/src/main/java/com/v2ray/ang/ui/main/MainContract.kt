package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.haima.BootstrapStatus
import com.v2ray.ang.haima.SorenAdsSettings
import com.v2ray.ang.haima.SorenAppSettings

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connecting : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data object SelectingServer : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val bootstrapStatus: BootstrapStatus = BootstrapStatus.Loading(),
    val ads: SorenAdsSettings = SorenAdsSettings(),
    val appSettings: SorenAppSettings = SorenAppSettings(),
    val managedServerCount: Int = 0,
    val selectedLatencyMillis: Long? = null,
    val connectedAtEpochMillis: Long? = null,
    val successfulConnectionEventId: Long = 0L
)

/**
 * The VPN core has started, but the connection result is deliberately not presented yet.
 *
 * Keeping [isRunning] true is important: the local HTTP proxy is already usable by AdMob and
 * the service can still be stopped safely.  [status] remains [MainStatus.Connecting] until the
 * one-shot ad gate completes (dismiss, no-fill/error, or timeout).
 */
internal fun MainUiState.awaitConnectionPresentation(): MainUiState = copy(
    isRunning = true,
    status = MainStatus.Connecting,
    connectedAtEpochMillis = null,
    successfulConnectionEventId = successfulConnectionEventId + 1L
)

/** Completes only the currently pending connection presentation; stale ad callbacks are ignored. */
internal fun MainUiState.completeConnectionPresentation(
    eventId: Long,
    nowEpochMillis: Long
): MainUiState {
    if (!isRunning || status !is MainStatus.Connecting || successfulConnectionEventId != eventId) {
        return this
    }
    return copy(
        status = MainStatus.Connected,
        connectedAtEpochMillis = nowEpochMillis
    )
}

/**
 * `null` means the connection ad gate owns route setup. This prevents two independent
 * collectors from racing and invalidating each other's proxy-route generation.
 */
internal fun MainUiState.normalAdRouteState(): Boolean? = when {
    !isRunning -> false
    status is MainStatus.Connecting -> null
    else -> true
}

/**
 * Applies raw service state without allowing StateRunning to bypass a pending presentation gate.
 */
internal fun MainUiState.withCoreRunningState(
    running: Boolean,
    clearTestingText: Boolean,
    nowEpochMillis: Long
): MainUiState {
    if (!running) {
        return copy(
            isRunning = false,
            connectedAtEpochMillis = null,
            status = if (!clearTestingText && isTesting) status else MainStatus.Disconnected
        )
    }

    val presentationPending = status is MainStatus.Connecting
    val nextStatus = when {
        !clearTestingText && isTesting -> status
        presentationPending -> MainStatus.Connecting
        else -> MainStatus.Connected
    }
    return copy(
        isRunning = true,
        connectedAtEpochMillis = when {
            presentationPending -> null
            connectedAtEpochMillis != null -> connectedAtEpochMillis
            else -> nowEpochMillis
        },
        status = nextStatus
    )
}

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction
}
