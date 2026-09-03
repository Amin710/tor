package com.v2ray.ang.ui.main

import android.net.TrafficStats
import android.os.Process
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.haima.BootstrapStage
import com.v2ray.ang.haima.BootstrapStatus
import com.v2ray.ang.haima.SorenBannerAd
import com.v2ray.ang.haima.SplashAdTunnelCoordinator
import com.v2ray.ang.haima.SplashAdTunnelState
import com.v2ray.ang.haima.isEntryGateComplete
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private val TornadoNavy = Color(0xFF071E42)
private val TornadoBlue = Color(0xFF238CF1)
private val TornadoCyan = Color(0xFF19BCEB)
private val TornadoPurple = Color(0xFF7044E8)
private val TornadoGold = Color(0xFFF5C735)
private val TornadoInk = Color(0xFF101828)
private val TornadoMuted = Color(0xFF778198)
private val TornadoPanel = Color(0xFFF4F7FD)
private val TornadoHeader = Brush.linearGradient(
    listOf(TornadoNavy, Color(0xFF174C93), TornadoPurple)
)

enum class SorenDrawerAction { Privacy, Share }

internal fun canEnterMainScreen(
    status: BootstrapStatus,
    minimumSplashElapsed: Boolean,
    splashAdGateComplete: Boolean = true
): Boolean = minimumSplashElapsed && splashAdGateComplete && status is BootstrapStatus.Ready

internal fun splashBootstrapStatus(
    bootstrapStatus: BootstrapStatus,
    splashAdState: SplashAdTunnelState
): BootstrapStatus {
    if (bootstrapStatus !is BootstrapStatus.Ready || splashAdState.isEntryGateComplete) {
        return bootstrapStatus
    }
    val stage = when (splashAdState) {
        SplashAdTunnelState.AwaitingBootstrap,
        SplashAdTunnelState.Ready,
        SplashAdTunnelState.CheckingExistingVpn -> BootstrapStage.CHECKING_AD_ROUTE
        SplashAdTunnelState.ImportingServers,
        is SplashAdTunnelState.Connecting -> BootstrapStage.CONNECTING_AD_ROUTE
        SplashAdTunnelState.LoadingAd -> BootstrapStage.LOADING_SPLASH_AD
        SplashAdTunnelState.StoppingTunnel -> BootstrapStage.FINALIZING
        is SplashAdTunnelState.Complete -> BootstrapStage.FINALIZING
    }
    return BootstrapStatus.Loading(stage)
}

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onDrawerAction: (SorenDrawerAction) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val splashAdTunnelState by SplashAdTunnelCoordinator.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var minimumSplashElapsed by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var dismissedMaintenanceMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        delay(1_450)
        minimumSplashElapsed = true
    }

    val canEnterApp = canEnterMainScreen(
        uiState.bootstrapStatus,
        minimumSplashElapsed,
        splashAdTunnelState.isEntryGateComplete
    )
    if (canEnterApp) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SorenDrawer(
                    onClose = { scope.launch { drawerState.close() } },
                    onPrivacy = {
                        scope.launch { drawerState.close() }
                        onDrawerAction(SorenDrawerAction.Privacy)
                    },
                    onShare = {
                        scope.launch { drawerState.close() }
                        onDrawerAction(SorenDrawerAction.Share)
                    },
                    onAbout = {
                        scope.launch { drawerState.close() }
                        showAbout = true
                    }
                )
            }
        ) {
            SorenHome(
                uiState = uiState,
                onMenu = { scope.launch { drawerState.open() } },
                onConnect = { onAction(MainAction.ToggleService) }
            )
        }
    } else {
        SorenSplash(
            status = splashBootstrapStatus(uiState.bootstrapStatus, splashAdTunnelState),
            onRetry = { onAction(MainAction.Initialize) }
        )
    }

    if (canEnterApp && showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.soren_about_body)) },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("OK") }
            }
        )
    }

    val updateRequired = uiState.bootstrapStatus as? BootstrapStatus.UpdateRequired
    if (updateRequired != null) {
        val policy = updateRequired.policy
        val playUrl = policy.playStoreUrl.takeIf(::isSafeHttpsUrl)
        val directUrl = policy.directUrl.takeIf(::isSafeHttpsUrl)
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(policy.title.ifBlank { stringResource(R.string.soren_update_required_title) })
            },
            text = {
                Text(policy.message.ifBlank { stringResource(R.string.soren_update_required_message) })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            playUrl != null -> onOpenUrl(playUrl)
                            directUrl != null -> onOpenUrl(directUrl)
                            else -> onAction(MainAction.Initialize)
                        }
                    }
                ) {
                    Text(
                        if (playUrl != null) stringResource(R.string.soren_update_from_play)
                        else if (directUrl != null) stringResource(R.string.soren_direct_download)
                        else stringResource(R.string.soren_retry)
                    )
                }
            },
            dismissButton = if (playUrl != null && directUrl != null) {
                {
                    TextButton(onClick = { onOpenUrl(directUrl) }) {
                        Text(stringResource(R.string.soren_direct_download))
                    }
                }
            } else {
                null
            }
        )
    } else if (canEnterApp) {
        val maintenanceMessage = uiState.appSettings.maintenanceMessage.trim()
        if (maintenanceMessage.isNotBlank() && maintenanceMessage != dismissedMaintenanceMessage) {
            AlertDialog(
                onDismissRequest = { dismissedMaintenanceMessage = maintenanceMessage },
                title = { Text(stringResource(R.string.soren_maintenance_title)) },
                text = { Text(maintenanceMessage) },
                confirmButton = {
                    TextButton(onClick = { dismissedMaintenanceMessage = maintenanceMessage }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            )
        }
    }
}

private fun isSafeHttpsUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) && value.length <= 2_048

@Composable
private fun SorenHome(
    uiState: MainUiState,
    onMenu: () -> Unit,
    onConnect: () -> Unit
) {
    val connectionPresented = uiState.isRunning && uiState.status is MainStatus.Connected
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White,
        bottomBar = {
            SorenBannerAd(
                settings = uiState.ads,
                modifier = Modifier
                    .background(Color.White)
                    .navigationBarsPadding()
                    .padding(top = 6.dp, bottom = 6.dp)
            )
        }
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(bottom = contentPadding.calculateBottomPadding())
        ) {
            val headerHeight = (maxHeight * 0.34f).coerceIn(235.dp, 330.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .clip(RoundedCornerShape(bottomStart = 54.dp, bottomEnd = 54.dp))
                        .background(TornadoHeader)
                        .statusBarsPadding()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_24dp),
                        contentDescription = "Menu",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(start = 22.dp, top = 12.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onMenu)
                            .padding(10.dp)
                    )
                    Image(
                        painter = painterResource(R.drawable.tornado_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 24.dp)
                            .size((headerHeight * 0.41f).coerceIn(104.dp, 138.dp))
                            .clip(RoundedCornerShape(28.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                ) {
                    SorenConnectButton(
                        connected = connectionPresented,
                        busy = uiState.status is MainStatus.SelectingServer ||
                            uiState.status is MainStatus.Connecting,
                        onClick = onConnect,
                        modifier = Modifier.offset(y = (-86).dp)
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.offset(y = (-64).dp)
                    ) {
                        Text(
                            text = when {
                                uiState.status is MainStatus.SelectingServer ->
                                    stringResource(R.string.soren_finding_best_server)
                                uiState.status is MainStatus.Connecting ->
                                    stringResource(R.string.soren_connecting)
                                connectionPresented -> stringResource(R.string.soren_connected)
                                else -> stringResource(R.string.soren_not_connected)
                            },
                            color = if (connectionPresented) TornadoCyan else TornadoMuted,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = rememberConnectionDuration(
                                connectionPresented,
                                uiState.connectedAtEpochMillis
                            ),
                            color = TornadoInk,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }

                    SorenServerCard(uiState, Modifier.offset(y = (-38).dp))
                    SorenStatistics(uiState, Modifier.offset(y = (-18).dp))

                    when (uiState.bootstrapStatus) {
                        BootstrapStatus.BackendSetupRequired -> Text(
                            stringResource(R.string.soren_backend_setup_required),
                            color = TornadoMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        is BootstrapStatus.Error -> Text(
                            stringResource(R.string.soren_configuration_unavailable),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        else -> Unit
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SorenConnectButton(
    connected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ring = if (connected) TornadoCyan else Color(0xFFE6EBF6)
    val accessibleState = stringResource(
        when {
            busy -> R.string.soren_connecting
            connected -> R.string.soren_connected
            else -> R.string.soren_not_connected
        }
    )
    Box(
        modifier = modifier
            .size(218.dp)
            .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(Color.White)
            .semantics(mergeDescendants = true) {
                stateDescription = accessibleState
            }
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            drawCircle(
                color = ring,
                style = Stroke(width = 7.dp.toPx())
            )
            drawCircle(
                color = if (connected) Color.White.copy(alpha = 0.76f) else TornadoPurple.copy(alpha = 0.68f),
                radius = size.minDimension / 2f - 14.dp.toPx(),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 12f))
                )
            )
        }
        if (busy) {
            SorenConnectingRing()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(58.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.56f, size.height * 0.03f)
                    lineTo(size.width * 0.18f, size.height * 0.53f)
                    quadraticTo(
                        size.width * 0.13f,
                        size.height * 0.64f,
                        size.width * 0.31f,
                        size.height * 0.64f
                    )
                    lineTo(size.width * 0.45f, size.height * 0.64f)
                    lineTo(size.width * 0.35f, size.height * 0.97f)
                    lineTo(size.width * 0.84f, size.height * 0.42f)
                    quadraticTo(
                        size.width * 0.91f,
                        size.height * 0.30f,
                        size.width * 0.70f,
                        size.height * 0.30f
                    )
                    lineTo(size.width * 0.58f, size.height * 0.30f)
                    close()
                }
                drawPath(path, if (connected) TornadoCyan else TornadoGold)
            }
            Text(
                text = stringResource(if (connected) R.string.soren_stop else R.string.soren_lets_go),
                color = if (connected) TornadoBlue else TornadoInk,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SorenConnectingRing() {
    val transition = rememberInfiniteTransition(label = "connect-waiting-ring")
    val rotationDegrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_050, easing = LinearEasing)
        ),
        label = "connect-waiting-ring-rotation"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
            .rotate(rotationDegrees)
    ) {
        val strokeWidth = 7.dp.toPx()
        val inset = strokeWidth / 2f
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    TornadoCyan.copy(alpha = 0.08f),
                    TornadoCyan,
                    TornadoBlue,
                    TornadoPurple,
                    TornadoPurple.copy(alpha = 0.08f)
                )
            ),
            startAngle = -90f,
            sweepAngle = 286f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SorenServerCard(uiState: MainUiState, modifier: Modifier = Modifier) {
    val connectionPresented = uiState.isRunning && uiState.status is MainStatus.Connected
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(TornadoPanel)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (connectionPresented) TornadoBlue else Color(0xFFE4E9F4)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(26.dp)) {
                drawLine(
                    color = if (connectionPresented) Color.White else TornadoMuted,
                    start = Offset(size.width * 0.28f, size.height * 0.12f),
                    end = Offset(size.width * 0.28f, size.height * 0.88f),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawArc(
                    color = if (connectionPresented) Color.White else TornadoMuted,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.18f),
                    size = Size(size.width * 0.52f, size.height * 0.45f),
                    style = Stroke(2.5.dp.toPx())
                )
            }
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                stringResource(R.string.soren_automatic_server),
                color = TornadoInk,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = uiState.selectedLatencyMillis?.let { "$it ms" } ?: "—",
                color = TornadoMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text(
            text = "${uiState.managedServerCount}",
            color = TornadoNavy,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun SorenStatistics(uiState: MainUiState, modifier: Modifier = Modifier) {
    val connectionPresented = uiState.isRunning && uiState.status is MainStatus.Connected
    val speed by rememberTrafficSpeed(connectionPresented)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SorenStat(
            label = stringResource(R.string.soren_download),
            value = "%.1f Mbps".format(Locale.US, speed.downloadMbps),
            modifier = Modifier.weight(1f)
        )
        SorenStat(
            label = stringResource(R.string.soren_upload),
            value = "%.1f Mbps".format(Locale.US, speed.uploadMbps),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SorenStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = TornadoMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(
            value,
            color = TornadoInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
private fun SorenDrawer(
    onClose: () -> Unit,
    onPrivacy: () -> Unit,
    onShare: () -> Unit,
    onAbout: () -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 330.dp)
            .fillMaxWidth(0.82f),
        drawerContainerColor = Color.White
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(bottomEnd = 48.dp))
                    .background(TornadoHeader),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.tornado_logo),
                    contentDescription = null,
                    modifier = Modifier.size(156.dp).clip(RoundedCornerShape(30.dp))
                )
            }
            Spacer(Modifier.height(28.dp))
            SorenDrawerItem(R.drawable.ic_qu_start_24dp, R.string.soren_home, true, onClose)
            SorenDrawerItem(R.drawable.ic_privacy_24dp, R.string.soren_privacy, false, onPrivacy)
            SorenDrawerItem(R.drawable.ic_share_24dp, R.string.soren_share, false, onShare)
            SorenDrawerItem(R.drawable.ic_about_24dp, R.string.soren_about, false, onAbout)
        }
    }
}

@Composable
private fun SorenDrawerItem(
    icon: Int,
    label: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Text(
                stringResource(label),
                color = TornadoInk,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = TornadoBlue,
                modifier = Modifier.size(27.dp)
            )
        },
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Color(0xFFEAF1FF),
            unselectedContainerColor = Color.Transparent
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
    )
}

@Composable
private fun rememberConnectionDuration(running: Boolean, startedAt: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, startedAt) {
        while (isActive && running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = if (running && startedAt != null) ((now - startedAt) / 1_000).coerceAtLeast(0) else 0
    return "%02d:%02d:%02d".format(
        Locale.US,
        seconds / 3_600,
        (seconds % 3_600) / 60,
        seconds % 60
    )
}

private data class TrafficSpeed(val downloadMbps: Double = 0.0, val uploadMbps: Double = 0.0)

@Composable
private fun rememberTrafficSpeed(running: Boolean) = produceState(TrafficSpeed(), running) {
    if (!running) {
        value = TrafficSpeed()
        return@produceState
    }
    val uid = Process.myUid()
    var previousRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
    var previousTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
    while (isActive) {
        delay(1_000)
        val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(previousRx)
        val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(previousTx)
        value = TrafficSpeed(
            downloadMbps = (rx - previousRx) * 8.0 / 1_000_000.0,
            uploadMbps = (tx - previousTx) * 8.0 / 1_000_000.0
        )
        previousRx = rx
        previousTx = tx
    }
}
