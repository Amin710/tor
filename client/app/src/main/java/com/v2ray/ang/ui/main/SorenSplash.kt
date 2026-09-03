package com.v2ray.ang.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.haima.BootstrapStage
import com.v2ray.ang.haima.BootstrapStatus
import kotlin.math.roundToInt

private val SplashTop = Color(0xFF071E42)
private val SplashMiddle = Color(0xFF1755A3)
private val SplashBottom = Color(0xFF7044E8)
private val SplashInk = Color(0xFF06172F)

@Composable
internal fun SorenSplash(status: BootstrapStatus, onRetry: () -> Unit) {
    val hasError = status is BootstrapStatus.Error ||
        status is BootstrapStatus.BackendSetupRequired
    val targetProgress = splashProgress(status)

    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "sorenSplashProgress"
    )
    val progressLabel = splashProgressLabel(status)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SplashTop, SplashMiddle, SplashBottom)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        SplashPattern(Modifier.fillMaxSize())

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(144.dp)
                    .shadow(
                        elevation = 22.dp,
                        shape = RoundedCornerShape(38.dp),
                        ambientColor = SplashInk.copy(alpha = 0.22f),
                        spotColor = SplashInk.copy(alpha = 0.22f)
                    )
                    .clip(RoundedCornerShape(38.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .padding(7.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.tornado_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(32.dp)),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp
            )
            Text(
                text = stringResource(R.string.soren_splash_tagline),
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 7.dp)
            )

            if (hasError) {
                Spacer(Modifier.height(28.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.soren_splash_connection_error),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.soren_splash_connection_error_body),
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = SplashMiddle
                        ),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.soren_retry),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (!hasError) Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 30.dp, vertical = 30.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(progressLabel),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(progress * 100).roundToInt()}%",
                    color = Color.White.copy(alpha = 0.84f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp)
                    .height(7.dp)
                    .clip(CircleShape),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.22f),
                gapSize = 0.dp,
                drawStopIndicator = { }
            )
            Text(
                text = stringResource(R.string.soren_splash_connection_note),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 13.dp)
            )
        }
    }
}

internal fun splashProgress(status: BootstrapStatus): Float = when (status) {
    is BootstrapStatus.Loading -> when (status.stage) {
        BootstrapStage.PREPARING -> 0.06f
        BootstrapStage.DOWNLOADING_PRIMARY -> 0.15f
        BootstrapStage.DECODING_PRIMARY -> 0.32f
        BootstrapStage.VERIFYING_PRIMARY -> 0.45f
        BootstrapStage.DOWNLOADING_FALLBACK -> 0.52f
        BootstrapStage.DECODING_FALLBACK -> 0.64f
        BootstrapStage.VERIFYING_FALLBACK -> 0.76f
        BootstrapStage.IMPORTING -> 0.84f
        BootstrapStage.CHECKING_AD_ROUTE -> 0.88f
        BootstrapStage.CONNECTING_AD_ROUTE -> 0.91f
        BootstrapStage.LOADING_SPLASH_AD -> 0.95f
        BootstrapStage.FINALIZING -> 0.98f
    }
    BootstrapStatus.Ready -> 1f
    is BootstrapStatus.UpdateRequired -> 1f
    is BootstrapStatus.Error,
    BootstrapStatus.BackendSetupRequired -> 0f
}

private fun splashProgressLabel(status: BootstrapStatus): Int = when (status) {
    is BootstrapStatus.Loading -> when (status.stage) {
        BootstrapStage.PREPARING -> R.string.soren_splash_preparing
        BootstrapStage.DOWNLOADING_PRIMARY -> R.string.soren_splash_downloading_primary
        BootstrapStage.DECODING_PRIMARY,
        BootstrapStage.DECODING_FALLBACK -> R.string.soren_splash_decoding
        BootstrapStage.VERIFYING_PRIMARY,
        BootstrapStage.VERIFYING_FALLBACK -> R.string.soren_splash_verifying
        BootstrapStage.DOWNLOADING_FALLBACK -> R.string.soren_splash_downloading_fallback
        BootstrapStage.IMPORTING -> R.string.soren_splash_importing
        BootstrapStage.CHECKING_AD_ROUTE -> R.string.soren_splash_ad_route
        BootstrapStage.CONNECTING_AD_ROUTE -> R.string.soren_splash_ad_connecting
        BootstrapStage.LOADING_SPLASH_AD -> R.string.soren_splash_loading_ad
        BootstrapStage.FINALIZING -> R.string.soren_splash_finalizing
    }
    else -> R.string.soren_splash_ready
}

@Composable
private fun SplashPattern(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(
            color = Color.White.copy(alpha = 0.07f),
            radius = size.minDimension * 0.40f,
            center = Offset(size.width * 0.88f, size.height * 0.10f)
        )
        drawCircle(
            color = SplashInk.copy(alpha = 0.06f),
            radius = size.minDimension * 0.31f,
            center = Offset(size.width * 0.02f, size.height * 0.80f)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.08f),
            radius = size.minDimension * 0.54f,
            center = Offset(size.width * 0.50f, size.height * 0.48f),
            style = Stroke(width = 1.2.dp.toPx())
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.05f),
            radius = size.minDimension * 0.45f,
            center = Offset(size.width * 0.50f, size.height * 0.48f),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}
