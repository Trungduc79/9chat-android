package vn.chat9.app.ui.call

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import vn.chat9.app.call.TextureViewRenderer
import vn.chat9.app.util.UrlUtils

@Composable
fun CallScreen(
    peerName: String,
    peerAvatar: String?,
    isVideo: Boolean,
    isIncoming: Boolean,
    callStatus: String,
    callDuration: Int,
    isMuted: Boolean,
    isSpeaker: Boolean,
    isCameraOn: Boolean,
    remoteStream: MediaStream? = null,
    localStream: MediaStream? = null,
    remoteVideoTrack: VideoTrack? = null,
    localVideoTrack: VideoTrack? = null,
    eglContext: EglBase.Context? = null,
    remoteRenderer: SurfaceViewRenderer? = null,
    localRenderer: TextureViewRenderer? = null,
    // V2 signal: true when the remote peer's media tracks have arrived on
    // the PeerConnection (onTrack fired). V1 relied on remoteStream != null
    // for the same check; V2 doesn't pass a MediaStream but flags this boolean
    // so the immersive-video / hide-avatar logic below still flips correctly
    // the moment the call is actually connected.
    isRemoteVideoReady: Boolean = false,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit = {}
) {
    val avatarUrl = UrlUtils.toFullUrl(peerAvatar)
    val durationText = if (callDuration > 0) {
        "%02d:%02d".format(callDuration / 60, callDuration % 60)
    } else ""

    val remoteReady = remoteStream != null || isRemoteVideoReady
    val showAvatar = !isVideo || !remoteReady
    val isRingingIncoming = isIncoming && callStatus == "Cuộc gọi đến..."
    // Immersive mode applies only when a video call is actually showing video
    // (remote stream present). Tap toggles the overlay + system bars.
    val isVideoImmersive = isVideo && remoteReady && !isRingingIncoming
    var controlsVisible by remember { mutableStateOf(true) }

    // Keep the screen on (no auto-timeout, no auto-lock) for the entire time
    // CallScreen is composed. Paired with ProximityWakeLock — when the phone
    // is brought to the ear, the proximity sensor turns the display off; when
    // held away, this flag ensures it stays on instead of timing out mid-call.
    val callView = LocalView.current
    DisposableEffect(Unit) {
        callView.keepScreenOn = true
        onDispose { callView.keepScreenOn = false }
    }

    // Full-screen immersive system UI for ALL call states (incoming, outgoing,
    // in-call — audio or video). Activity is already running edge-to-edge
    // (decorFitsSystemWindows = false set in MainActivity.onCreate), so:
    //   - Status bar stays visible but transparent (clock/signal still show).
    //   - Navigation bar is hidden; user can swipe up briefly to reveal it,
    //     then it auto-hides (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
    //   - Light icons (white) on the dark/purple call background.
    // Restored on dispose so the rest of the app gets its previous bar styling.
    DisposableEffect(Unit) {
        val window = (callView.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowInsetsControllerCompat(it, callView) }
        val prevStatusColor = window?.statusBarColor ?: 0
        val prevNavColor = window?.navigationBarColor ?: 0
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        val prevBehavior = controller?.systemBarsBehavior

        if (window != null && controller != null) {
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }

        onDispose {
            if (window != null && controller != null) {
                @Suppress("DEPRECATION")
                window.statusBarColor = prevStatusColor
                @Suppress("DEPRECATION")
                window.navigationBarColor = prevNavColor
                if (prevLightStatus != null) controller.isAppearanceLightStatusBars = prevLightStatus
                if (prevLightNav != null) controller.isAppearanceLightNavigationBars = prevLightNav
                if (prevBehavior != null) controller.systemBarsBehavior = prevBehavior
                controller.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    // Lighter tint of the brand purple, fully opaque so the app below is hidden.
    val bgBrush = when {
        isVideo && remoteReady -> Brush.linearGradient(listOf(Color.Black, Color.Black))
        isRingingIncoming -> Brush.linearGradient(listOf(
            Color(0xFFD7BDE2), // pale lavender top
            Color(0xFFBB8FCE)  // medium light purple bottom
        ))
        else -> Brush.linearGradient(listOf(Color(0xFF8E44AD), Color(0xFF3E1F91)))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .then(
                // In video-immersive mode, a tap on empty space toggles the overlay.
                if (isVideoImmersive) Modifier.pointerInput(Unit) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                } else Modifier
            )
    ) {
        // Remote video
        if (isVideo && remoteRenderer != null) {
            AndroidView(
                factory = { ctx ->
                    remoteRenderer.also {
                        (it.parent as? ViewGroup)?.removeView(it)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Local video PIP — draggable within bounds. Base position is top-right
        // (40dp from top, 16dp from right). User can drag to any other corner
        // while keeping it out of the bottom controls area. Movement is
        // smoothed by animateFloatAsState so snapping and bar-toggle shifts
        // glide instead of jumping.
        if (isVideo && localRenderer != null && isCameraOn) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
            val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
            val pipWpx = with(density) { 90.dp.toPx() }
            val pipHpx = with(density) { 120.dp.toPx() }
            val sideMarginPx = with(density) { 16.dp.toPx() }
            val topPaddingPx = with(density) { 40.dp.toPx() }
            // Bottom reserved area for buttons (action row + text + breathing
            // room). Empirical: button row ≈ 90dp + bottom spacer ≈ 50 +
            // 10% of screen height.
            val bottomReservedPx = with(density) {
                (90.dp + 50.dp + configuration.screenHeightDp.dp * 0.10f).toPx()
            }

            // Drag offset state, in pixels, relative to base TopEnd position.
            var dragX by remember { mutableStateOf(0f) }
            var dragY by remember { mutableStateOf(0f) }

            // Bounds: negative X = move left, positive Y = move down.
            val minX = -(screenWidthPx - pipWpx - 2f * sideMarginPx)
            val maxX = 0f
            val minY = 0f
            val maxY = (screenHeightPx - pipHpx - bottomReservedPx - topPaddingPx).coerceAtLeast(0f)

            // Smooth the offset — responsive during drag (StiffnessHigh, no
            // bounce) yet gently settles on release / external shifts.
            val animX by androidx.compose.animation.core.animateFloatAsState(
                targetValue = dragX,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessHigh
                ),
                label = "pipX"
            )
            val animY by androidx.compose.animation.core.animateFloatAsState(
                targetValue = dragY,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessHigh
                ),
                label = "pipY"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
                    .offset {
                        androidx.compose.ui.unit.IntOffset(animX.toInt(), animY.toInt())
                    }
                    .size(width = 90.dp, height = 120.dp)
                    // TextureView (unlike SurfaceView) participates in the normal
                    // view compositing pipeline, so Compose's .clip() with
                    // RoundedCornerShape rounds the actual video content.
                    .clip(RoundedCornerShape(14.dp))
                    .zIndex(2f)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragX = (dragX + dragAmount.x).coerceIn(minX, maxX)
                            dragY = (dragY + dragAmount.y).coerceIn(minY, maxY)
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        localRenderer.also {
                            (it.parent as? android.view.ViewGroup)?.removeView(it)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Overlay content (timer/name/buttons). Smooth non-bouncy spring
        // for a soft natural motion. DampingRatioNoBouncy = no overshoot,
        // StiffnessMediumLow = longer, gentler trajectory.
        val overlaySpec = androidx.compose.animation.core.spring<Float>(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )
        val overlayOffsetSpec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )
        AnimatedVisibility(
            visible = !isVideoImmersive || controlsVisible,
            enter = fadeIn(overlaySpec) + slideInVertically(overlayOffsetSpec) { it / 6 },
            exit = fadeOut(overlaySpec) + slideOutVertically(overlayOffsetSpec) { it / 6 }
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isRingingIncoming) {
                // App title pinned to the very top center
                Spacer(Modifier.height(40.dp))
                Text(
                    "9CHAT",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
            Spacer(Modifier.weight(if (showAvatar) 0.3f else 0.05f))

            if (showAvatar) {
                // Avatar with pulsating ripple background
                Box(contentAlignment = Alignment.Center) {
                    RippleAura(baseRadiusDp = 75.dp)
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl, contentDescription = peerName,
                            modifier = Modifier.size(150.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.size(150.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(peerName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))

                // Name
                Text(peerName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
            }

            // Status / Timer
            Text(
                text = if (durationText.isNotEmpty()) durationText else callStatus,
                fontSize = if (showAvatar) 15.sp else 16.sp,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(Modifier.weight(if (showAvatar) 0.5f else 0.85f))

            // Buttons
            if (isIncoming && callStatus == "Cuộc gọi đến...") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallActionButton(Icons.Default.CallEnd, "Từ chối", Color(0xFFF44336), onReject)
                    AcceptCallButton(isVideo = isVideo, onClick = onAccept)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (!isVideo) {
                        // Audio: Loa | End | Mic
                        CallActionButton(
                            iconRes = if (isSpeaker) vn.chat9.app.R.drawable.ic_call_speaker_on else vn.chat9.app.R.drawable.ic_call_speaker_off,
                            label = if (isSpeaker) "Loa" else "Tai nghe",
                            color = if (isSpeaker) Color.White else Color.White.copy(alpha = 0.2f),
                            iconTint = if (isSpeaker) Color(0xFF8E44AD) else Color.White,
                            onClick = onToggleSpeaker
                        )
                        CallActionButton(Icons.Default.CallEnd, "Kết thúc", Color(0xFFF44336), onEnd)
                        CallActionButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Tắt mic" else "Mic",
                            color = if (isMuted) Color.White else Color.White.copy(alpha = 0.2f),
                            iconTint = if (isMuted) Color(0xFFF44336) else Color.White,
                            onClick = onToggleMute
                        )
                    } else {
                        // Video: Đảo | Mic | End | Camera
                        SwitchCameraButton(onClick = onSwitchCamera)
                        CallActionButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isMuted) "Tắt mic" else "Mic",
                            color = if (isMuted) Color.White else Color.White.copy(alpha = 0.2f),
                            iconTint = if (isMuted) Color(0xFFF44336) else Color.White,
                            onClick = onToggleMute
                        )
                        CallActionButton(Icons.Default.CallEnd, "Kết thúc", Color(0xFFF44336), onEnd)
                        CallActionButton(
                            if (isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Camera",
                            if (!isCameraOn) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.2f), onToggleCamera)
                    }
                }
            }
            // Bottom breathing room — +10% of screen height pushes all action buttons
            // (Từ chối / Nghe / Kết thúc / Mic / Loa / Camera) uniformly upward.
            val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            Spacer(Modifier.height(50.dp + screenHeightDp * 0.10f))
        }
        } // end AnimatedVisibility
    }
}

/**
 * Accept-call button (voice + video variants) with attention-grabbing animation:
 * - icon swings ±15° around its center (like a ringing phone)
 * - green background pulses with concentric ripple rings, same style as avatar aura
 */
@Composable
private fun AcceptCallButton(isVideo: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "acceptCallBtn")
    val rotation = transition.animateFloat(
        initialValue = -20f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(160, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "iconRotation"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp)
        ) {
            // Ripple rings around the button (reuse the same look as avatar aura).
            // baseRadiusDp slightly larger than button radius so the innermost ring
            // sits just outside the green circle.
            RippleAura(baseRadiusDp = 38.dp, ringCount = 3)

            // Button
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
            ) {
                Icon(
                    imageVector = if (isVideo) Icons.Default.Videocam else Icons.Default.Phone,
                    contentDescription = "Nghe",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer { rotationZ = rotation.value }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Nghe", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

/**
 * Flip-camera button with a one-shot 360° spin animation on each tap.
 * The target rotation is an accumulating counter × 360°, so every click adds a
 * full revolution — `animateFloatAsState` handles the tween smoothly even if
 * the user taps while a previous spin is still in flight.
 */
@Composable
private fun SwitchCameraButton(onClick: () -> Unit) {
    var spinCount by remember { mutableIntStateOf(0) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = spinCount * 360f,
        animationSpec = tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "switchCameraSpin"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = {
                spinCount++
                onClick()
            },
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            Icon(
                imageVector = Icons.Default.FlipCameraAndroid,
                contentDescription = "Đảo camera",
                tint = Color.White,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Đảo", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

/** Pulsating concentric circles emanating from the center (used behind avatar). */
@Composable
private fun RippleAura(baseRadiusDp: androidx.compose.ui.unit.Dp, ringCount: Int = 3) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "ripple-progress"
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val basePx = with(density) { baseRadiusDp.toPx() }
    val maxGrowthPx = with(density) { 95.dp.toPx() }

    Canvas(modifier = Modifier.size(baseRadiusDp * 2 + 200.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        for (i in 0 until ringCount) {
            val stagger = i.toFloat() / ringCount
            val p = ((progress.value + stagger) % 1f)
            val radius = basePx + p * maxGrowthPx
            val alpha = (1f - p) * 0.22f
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radius,
                center = center
            )
        }
    }
}

@Composable
fun CallActionButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, iconTint: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(70.dp).clip(CircleShape).background(color)
        ) {
            Icon(icon, label, tint = iconTint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

@Composable
fun CallActionButton(
    @androidx.annotation.DrawableRes iconRes: Int,
    label: String,
    color: Color,
    onClick: () -> Unit,
    iconTint: Color = Color.White
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(70.dp).clip(CircleShape).background(color)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}
