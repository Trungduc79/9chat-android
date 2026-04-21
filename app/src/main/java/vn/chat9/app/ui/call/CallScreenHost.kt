package vn.chat9.app.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.chat9.app.call.CallManager
import vn.chat9.app.call.CallViewModel
import vn.chat9.app.call.model.CallState
import vn.chat9.app.call.model.CallType

/**
 * Thin adapter that wires the V2 [CallViewModel] into the existing
 * [CallScreen] composable. Lets us keep the CallScreen UI verbatim — all
 * the parameter-driven rendering stays put — while the state source of
 * truth moves to the singleton [CallManager].
 *
 * MainActivity's call-overlay block is now just:
 *
 *   if (CallScreenHost.isVisible()) { CallScreenHost(onClose = ...) }
 *
 * Everything else (accept / reject / mute / speaker / camera toggle /
 * switch camera / end) routes through the ViewModel → CallManager.
 */
object CallScreenHost {

    /**
     * True while we should be showing a call overlay. IDLE and post-cleanup
     * transient states don't show the overlay.
     */
    @Composable
    fun isVisible(): Boolean {
        val state by CallManager.state.collectAsState()
        return state != CallState.IDLE
    }
}

@Composable
fun CallScreenHost(onClose: () -> Unit = {}) {
    val vm: CallViewModel = viewModel()
    val state by vm.state.collectAsState()
    val call by vm.currentCall.collectAsState()
    val micMuted by vm.micMuted.collectAsState()
    val speakerOn by vm.speakerOn.collectAsState()
    val cameraOn by vm.cameraOn.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val hasRemoteMedia by CallManager.hasRemoteMedia.collectAsState()

    if (state == CallState.IDLE || call == null) return

    val callStatus = when (state) {
        CallState.INIT, CallState.CONNECTING -> "Đang gọi..."
        CallState.RINGING ->
            if (call!!.isOutgoing) "Đang đổ chuông..." else "Cuộc gọi đến..."
        CallState.ACCEPTED -> "Đang kết nối..."
        CallState.IN_CALL -> "Đang gọi"
        CallState.RECONNECTING -> "Đang kết nối lại..."
        CallState.REJECTED -> "Cuộc gọi bị từ chối"
        CallState.BUSY -> "Người dùng đang bận"
        CallState.NO_ANSWER -> "Không trả lời"
        CallState.NETWORK -> "Mất kết nối"
        CallState.ENDED -> "Cuộc gọi kết thúc"
        CallState.IDLE -> ""
    }

    // Ensure renderers exist whenever we need to show video.
    if (call!!.type == CallType.VIDEO) {
        androidx.compose.runtime.LaunchedEffect(state) {
            if (state == CallState.ACCEPTED || state == CallState.IN_CALL ||
                state == CallState.RINGING || state == CallState.CONNECTING) {
                CallManager.createRenderers()
            }
        }
    }

    CallScreen(
        peerName = call!!.peerName,
        peerAvatar = call!!.peerAvatar,
        isVideo = call!!.type == CallType.VIDEO,
        isIncoming = !call!!.isOutgoing,
        callStatus = callStatus,
        callDuration = elapsed,
        isMuted = micMuted,
        isSpeaker = speakerOn,
        isCameraOn = cameraOn,
        remoteStream = null,
        localStream = null,
        remoteVideoTrack = null,
        localVideoTrack = null,
        eglContext = CallManager.getEglContext(),
        remoteRenderer = CallManager.remoteRenderer,
        localRenderer = CallManager.localRenderer,
        isRemoteVideoReady = hasRemoteMedia,
        onAccept = { vm.accept() },
        onReject = {
            vm.reject()
            onClose()
        },
        onEnd = {
            vm.end()
            onClose()
        },
        onToggleMute = { vm.toggleMute() },
        onToggleSpeaker = { vm.toggleSpeaker() },
        onToggleCamera = { vm.toggleCamera() },
        onSwitchCamera = { vm.switchCamera() },
    )
}
