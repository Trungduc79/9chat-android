package vn.chat9.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import vn.chat9.app.call.audio.CallAudioRouter
import vn.chat9.app.call.audio.CallSoundPlayer
import vn.chat9.app.call.model.CallEndReason
import vn.chat9.app.call.model.CallModel
import vn.chat9.app.call.model.CallState
import vn.chat9.app.call.model.CallType
import vn.chat9.app.call.service.CallForegroundService
import vn.chat9.app.data.socket.ChatSocket

/**
 * V2 call engine. Singleton — observed via StateFlow by CallViewModel and
 * any other code that needs to know if a call is active.
 *
 * Replaces data/webrtc/CallManager.kt (kept side-by-side as V1 fallback
 * during phases 1–3, deleted in phase 4).
 *
 * Public API is thread-safe: every `handleIncomingFrom*` method and every
 * mutation wraps itself onto the Main looper internally. Call sites can
 * invoke from FCM, workers, coroutines, whatever — no leaked threading.
 *
 * Full design doc: claude_md/CLAUDE-CALL-SYSTEM-V2.md
 */
object CallManager {
    private const val TAG = "CallManagerV2"

    // ══════════════════════════════════════════════════════════════
    //  PUBLIC OBSERVABLE STATE
    // ══════════════════════════════════════════════════════════════
    private val _state = MutableStateFlow(CallState.IDLE)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _currentCall = MutableStateFlow<CallModel?>(null)
    val currentCall: StateFlow<CallModel?> = _currentCall.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _cameraOn = MutableStateFlow(true)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

    // True once a remote media track (audio and/or video) has actually
    // arrived on the PeerConnection. CallScreen reads this to flip from
    // "connecting with avatar" to full-screen remote video — without it
    // the UI would sit on the avatar even after ICE reached CONNECTED,
    // because the onTrack callback may arrive slightly after state=IN_CALL.
    private val _hasRemoteMedia = MutableStateFlow(false)
    val hasRemoteMedia: StateFlow<Boolean> = _hasRemoteMedia.asStateFlow()

    // Transient end-of-call events for the UI to show a toast / snackbar.
    // Needed since the call UI dismisses instantly on terminal state (no
    // 1.2s hold anymore) — observers of `state` can't tell "rejected" from
    // "no_answer" from the final IDLE they see. Emit via this SharedFlow
    // BEFORE state flips to IDLE.
    private val _endEvents = MutableSharedFlow<CallEndReason>(extraBufferCapacity = 4)
    val endEvents: SharedFlow<CallEndReason> = _endEvents.asSharedFlow()

    // Renderers — exposed so the UI can wrap them in AndroidView. Remote
    // uses SurfaceViewRenderer (full-screen, no clipping), local uses
    // TextureViewRenderer (PIP needs rounded corners — foot-gun #14).
    var remoteRenderer: SurfaceViewRenderer? = null
        private set
    var localRenderer: TextureViewRenderer? = null
        private set

    // ══════════════════════════════════════════════════════════════
    //  PRIVATE STATE
    // ══════════════════════════════════════════════════════════════
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var signalListenerRegistered = false

    private lateinit var appContext: Context
    private lateinit var socket: ChatSocket
    // Nullable sentinel (not lateinit) — if anything reaches handleIncomingInternal
    // before init() runs (e.g. an FCM delivered in a weird lifecycle corner), we
    // bail cleanly instead of crashing the process. App.onCreate normally wires
    // this up before FCMService is dispatched, so the null path is defensive only.
    private var audio: CallAudioRouter? = null

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peerConnection: PeerConnection? = null

    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var isFrontCamera: Boolean = true

    // Dynamic ICE config loaded from /api/v1/calls/init.php. Cached per
    // app process — refetch on init(). Static fallback in buildIceServers.
    @Volatile private var cachedIceServers: List<PeerConnection.IceServer>? = null

    // Busy / cleanup state
    @Volatile private var cleanupInProgress: Boolean = false
    @Volatile private var lastCleanupAtMs: Long = 0
    private val busyGraceWindowMs: Long = 400

    // Reconnect budget
    private var reconnectJob: Job? = null
    private val reconnectBudgetMs: Long = 10_000

    // Deep prewarm buffers
    private var bufferedOffer: SessionDescription? = null
    private var emitWhenOfferReady: Boolean = false
    private val outboundPendingCandidates = mutableListOf<IceCandidate>()
    private val inboundPendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet: Boolean = false
    private var pendingInboundOffer: SessionDescription? = null

    // Current call identifiers — @Volatile so the WebRTC ICE callback thread
    // sees the value Main thread just wrote in the call_signaling handler
    // without any cross-thread caching. All mutations happen inside the
    // outboundPendingCandidates monitor for atomicity with buffer flush.
    @Volatile
    private var callId: String? = null
    private var isVideoCall: Boolean = false

    // Wall-clock timestamp the first time ICE reached CONNECTED for this call.
    // Source of truth for the call-duration counter shown in CallScreen. Set
    // once and never reset until a new call starts (resetBuffers) so that
    // transient RECONNECTING windows don't roll the counter back to 0. The
    // UI reads it as `(System.currentTimeMillis() - connectedAtMs) / 1000`.
    @Volatile private var connectedAtMs: Long = 0L
    fun getConnectedAtMs(): Long = connectedAtMs

    // ══════════════════════════════════════════════════════════════
    //  INIT
    // ══════════════════════════════════════════════════════════════
    /**
     * Idempotent. Call from App.onCreate so SoundPool + signal listener
     * are ready before the first call arrives.
     */
    @Synchronized
    fun init(context: Context, chatSocket: ChatSocket) {
        val wasInit = ::appContext.isInitialized
        appContext = context.applicationContext
        socket = chatSocket
        if (!wasInit) {
            audio = CallAudioRouter(appContext)
            CallSoundPlayer.preloadAll(appContext)
        }
        ensureFactory()
        ensureSignalListener()
    }

    private fun ensureFactory() {
        if (peerConnectionFactory != null) return
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * True while the device can't reliably start a brand-new call.
     * Covers in-progress cleanup + the grace window after cleanup.
     */
    fun isBusy(): Boolean {
        if (cleanupInProgress) return true
        if (peerConnection != null) return true
        if (System.currentTimeMillis() - lastCleanupAtMs < busyGraceWindowMs) return true
        return false
    }

    // ══════════════════════════════════════════════════════════════
    //  CALLER ENTRY POINT
    // ══════════════════════════════════════════════════════════════
    /**
     * Start an outgoing call. Returns true if the call was initiated,
     * false if the device is busy.
     */
    fun initiateCall(
        roomId: Int,
        calleeId: Int,
        calleeName: String,
        calleeAvatar: String?,
        type: CallType,
    ): Boolean {
        if (!isMainThread()) {
            var result = false
            mainHandler.post { result = initiateCall(roomId, calleeId, calleeName, calleeAvatar, type) }
            return result
        }
        if (isBusy()) return false
        if (!hasRequiredPermissions(type == CallType.VIDEO)) return false

        resetBuffers()
        isVideoCall = (type == CallType.VIDEO)
        callId = null  // server assigns; we'll adopt on call_signaling

        _currentCall.value = CallModel(
            callId = "",
            peerUserId = calleeId,
            peerName = calleeName,
            peerAvatar = calleeAvatar,
            roomId = roomId,
            type = type,
            isOutgoing = true,
        )
        _state.value = CallState.INIT

        // Caller full prewarm: factory + PC + local tracks + offer.
        // The offer computation runs in parallel with the server roundtrip
        // that eventually delivers call_signaling (with the real callId).
        audio?.enterOutgoingRinging(isVideoCall)
        createPeerConnection()
        captureLocalMedia(isVideoCall)
        createAndBufferOffer()
        CallSoundPlayer.startConnecting(appContext)

        // Emit call_initiate — server replies with call_signaling (callId)
        // which triggers sendBufferedOffer via the signal listener.
        socket.emit("call_initiate", JSONObject().apply {
            put("room_id", roomId)
            put("callee_id", calleeId)
            put("call_type", type.wire)
        })

        startForegroundService("Đang gọi…")
        return true
    }

    // ══════════════════════════════════════════════════════════════
    //  CALLEE ENTRY POINTS
    // ══════════════════════════════════════════════════════════════
    /** Called from socket `call_incoming` event. */
    fun handleIncomingFromSocket(data: JSONObject) {
        runOnMain { handleIncomingInternal(data) }
    }

    /** Called from FCMService.onMessageReceived (background thread). */
    fun handleIncomingFromFcm(data: Map<String, String>) {
        val json = JSONObject()
        data.forEach { (k, v) -> json.put(k, v) }
        runOnMain { handleIncomingInternal(json) }
    }

    /** Called from MainActivity when the notification fullScreenIntent fires. */
    fun handleIncomingFromIntent(
        callId: String, callerId: Int, callerName: String, callerAvatar: String?,
        roomId: Int, type: CallType,
    ) {
        val json = JSONObject().apply {
            put("call_id", callId)
            put("caller_id", callerId)
            put("caller_name", callerName)
            put("caller_avatar", callerAvatar ?: "")
            put("room_id", roomId)
            put("call_type", type.wire)
        }
        runOnMain { handleIncomingInternal(json) }
    }

    private fun handleIncomingInternal(data: JSONObject) {
        val incomingCid = data.optString("call_id")

        // Dedupe: same call_id delivered via both FCM and socket paths
        // (common on lock-screen — FCM wakes the phone, the socket reconnects
        // a fraction of a second later and re-emits call_incoming). Without
        // this guard the second delivery saw state=RINGING and emitted
        // call_end(busy), which the user experienced as "rung một hồi rồi
        // báo máy bận".
        if (incomingCid.isNotEmpty() && callId == incomingCid) {
            Log.d(TAG, "Duplicate call_incoming for $incomingCid — ignoring")
            return
        }

        // Busy collision — real collision, different call_id while we're
        // actively in a call. Only the "in-flight" states count as busy;
        // the terminal + settled states do NOT. Otherwise a new incoming
        // call arriving inside the 1200ms terminal-display window (state
        // held at REJECTED/BUSY/NO_ANSWER/NETWORK before flipping to IDLE)
        // gets wrongly rejected with busy even though the user is free.
        val activeStates = setOf(
            CallState.INIT, CallState.CONNECTING, CallState.RINGING,
            CallState.ACCEPTED, CallState.IN_CALL, CallState.RECONNECTING,
        )
        if (isBusy() || _state.value in activeStates) {
            if (incomingCid.isNotEmpty()) {
                socket.emit("call_end", JSONObject().apply {
                    put("call_id", incomingCid)
                    put("reason", CallEndReason.BUSY.wire)
                })
            }
            return
        }

        resetBuffers()
        // JSONObject.optString returns the literal 4-char string "null" when
        // the JSON value is null. Strip that, "undefined", and blanks so we
        // fall back to the next resolver instead of rendering "null" as a name.
        fun JSONObject.safeStr(key: String): String? {
            if (isNull(key)) return null
            val s = optString(key, "").trim()
            return if (s.isBlank() || s.equals("null", true) || s.equals("undefined", true)) null else s
        }
        val cid = incomingCid
        val callerId = data.optInt("caller_id")
        val callerName = data.safeStr("caller_alias")
            ?: data.safeStr("caller_name")
            ?: "Người gọi"
        val callerAvatar = data.safeStr("caller_avatar")
        val roomId = data.optInt("room_id")
        val type = CallType.fromWire(data.optString("call_type"))

        callId = cid
        isVideoCall = (type == CallType.VIDEO)
        _currentCall.value = CallModel(
            callId = cid,
            peerUserId = callerId,
            peerName = callerName,
            peerAvatar = callerAvatar,
            roomId = roomId,
            type = type,
            isOutgoing = false,
        )
        _state.value = CallState.RINGING

        // Callee prewarm: factory + PC ready, but DO NOT capture local
        // media and DO NOT change audio mode — ringtone needs MODE_NORMAL
        // so it routes to the external speaker. Mic capture happens at Accept.
        audio?.enterIncomingRinging()
        createPeerConnection()
        // Foreground ringtone (no vibration — user is holding the phone).
        // Background ringtone is started by FCMService on the lock screen
        // with vibrate3=true. CallSoundPlayer's `active` flag prevents
        // double-start if both paths hit.
        CallSoundPlayer.startIncomingRingtone(appContext, vibrate3 = false)
        // Offers that arrive now get buffered in pendingInboundOffer (see
        // setupSignalListener); flushed on acceptCall().
    }

    /**
     * FCM call_event=dismiss — the server says this call ended elsewhere
     * (other device answered, or caller cancelled, etc).
     */
    fun handleRemoteDismiss(callIdOnMsg: String, reason: String) {
        runOnMain {
            if (callId == callIdOnMsg && _state.value == CallState.RINGING) {
                doEndCall(CallEndReason.fromWire(reason), emitToServer = false)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  USER ACTIONS
    // ══════════════════════════════════════════════════════════════
    fun acceptCall() = runOnMain {
        if (_state.value != CallState.RINGING) {
            Log.w(TAG, "acceptCall ignored — state=${_state.value}")
            return@runOnMain
        }
        Log.d(TAG, "acceptCall: callId=$callId isVideo=$isVideoCall hasPendingOffer=${pendingInboundOffer != null}")
        _state.value = CallState.ACCEPTED
        CallSoundPlayer.stopIncomingRingtone()
        audio?.enterInCall(isVideoCall)
        captureLocalMedia(isVideoCall)
        startForegroundService("Đang kết nối…")
        // Defer call_accept emit until the answer SDP is set locally, so the
        // caller can't race us with ICE before our PC is ready. Previous
        // condition `pendingInboundOffer == null` was always true here because
        // processPendingOffer() clears it synchronously — the deferred emit
        // never actually fired from the answer callback. Capture hadOffer
        // BEFORE calling processPendingOffer so the branching is correct.
        val hadOffer = (pendingInboundOffer != null)
        pendingAcceptEmit = true
        processPendingOffer()
        if (!hadOffer) {
            // Caller hasn't deep-prewarmed yet; their offer will follow. Safe
            // to tell the server we answered now — handleRemoteSignal's offer
            // branch will run the answer path when it arrives.
            flushAcceptEmit()
        }
        // If hadOffer: flushAcceptEmit fires from createAnswer→setLocal→onSetSuccess.
    }

    // True while acceptCall is waiting for createAnswer → setLocalDescription
    // to complete before firing call_accept to the server (fix [C]).
    @Volatile private var pendingAcceptEmit: Boolean = false

    private fun flushAcceptEmit() {
        if (!pendingAcceptEmit) return
        pendingAcceptEmit = false
        val cid = callId ?: return
        socket.emit("call_accept", JSONObject().put("call_id", cid))
    }

    fun rejectCall() = runOnMain {
        if (_state.value != CallState.RINGING) return@runOnMain
        val cid = callId ?: return@runOnMain
        socket.emit("call_end", JSONObject().apply {
            put("call_id", cid)
            put("reason", CallEndReason.REJECTED.wire)
        })
        doEndCall(CallEndReason.REJECTED, emitToServer = false)
    }

    fun endCall() = runOnMain {
        val cid = callId
        if (cid != null) {
            socket.emit("call_end", JSONObject().apply {
                put("call_id", cid)
                put("reason", CallEndReason.ENDED.wire)
            })
        }
        doEndCall(CallEndReason.ENDED, emitToServer = false)
    }

    /** Returns the new muted state (true = muted). */
    fun toggleMute(): Boolean {
        val muted = !_micMuted.value
        _micMuted.value = muted
        try { localAudioTrack?.setEnabled(!muted) } catch (_: Exception) {}
        audio?.setMicMute(muted)
        return muted
    }

    /** Returns the new speaker-on state. */
    fun toggleSpeaker(): Boolean {
        val on = !_speakerOn.value
        _speakerOn.value = on
        audio?.setSpeaker(on)
        return on
    }

    /** Returns the new camera-on state. */
    fun toggleCamera(): Boolean {
        val on = !_cameraOn.value
        _cameraOn.value = on
        try { localVideoTrack?.setEnabled(on) } catch (_: Exception) {}
        return on
    }

    fun switchCamera() {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                isFrontCamera = isFrontFacing
            }
            override fun onCameraSwitchError(error: String) {
                Log.e(TAG, "Camera switch error: $error")
            }
        })
    }

    fun isUsingFrontCamera(): Boolean = isFrontCamera

    // ══════════════════════════════════════════════════════════════
    //  RENDERERS
    // ══════════════════════════════════════════════════════════════
    fun createRenderers() = runOnMain {
        val egl = eglBase?.eglBaseContext ?: return@runOnMain
        if (remoteRenderer == null) {
            remoteRenderer = SurfaceViewRenderer(appContext).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            }
        }
        if (localRenderer == null) {
            localRenderer = TextureViewRenderer(appContext).apply {
                init(egl)
                setMirror(true)
            }
        }
        try { remoteVideoTrack?.addSink(remoteRenderer) } catch (_: Exception) {}
        try { localVideoTrack?.addSink(localRenderer) } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    //  INTERNAL — PeerConnection + WebRTC
    // ══════════════════════════════════════════════════════════════
    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 10
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Atomically read callId + (buffer | emit). Without this lock,
                // a race lets a candidate land in outboundPendingCandidates
                // AFTER call_signaling has already flushed the buffer — the
                // candidate then sits orphaned, server never gets it, ICE
                // negotiation stalls and the user sees "Đang kết nối" forever.
                synchronized(outboundPendingCandidates) {
                    val cid = callId
                    if (cid.isNullOrEmpty()) {
                        outboundPendingCandidates.add(candidate)
                    } else {
                        emitCandidate(cid, candidate)
                    }
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                runOnMain { onPeerConnectionState(state) }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    try { track.addSink(remoteRenderer) } catch (_: Exception) {}
                }
                if (track is AudioTrack) {
                    remoteAudioTrack = track
                    track.setEnabled(true)
                }
                // Flag that remote media is flowing — CallScreen uses this
                // to switch from avatar/connecting overlay to full-screen
                // video. Post to Main so the StateFlow observer runs on UI.
                runOnMain { _hasRemoteMedia.value = true }
            }
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun onPeerConnectionState(pcState: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "PC state=$pcState  callState=${_state.value}  callId=$callId")
        when (pcState) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                reconnectJob?.cancel()
                reconnectJob = null
                if (_state.value != CallState.IN_CALL) {
                    // First-ever CONNECTED for this call: stamp the wall
                    // clock so the duration counter ticks continuously from
                    // here through any later RECONNECTING → IN_CALL round
                    // trips. Only set it once — subsequent re-connects after
                    // a brief drop must NOT reset it (user spec 2026-04-20:
                    // "thời gian chạy độc lập không bị ảnh hưởng bởi lỗi gì").
                    if (connectedAtMs == 0L) {
                        connectedAtMs = System.currentTimeMillis()
                    }
                    _state.value = CallState.IN_CALL
                    CallSoundPlayer.stopAllCallerTones()
                    CallSoundPlayer.vibrateTick(appContext, 150L)
                    startForegroundService("Đang trong cuộc gọi")
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED,
            PeerConnection.PeerConnectionState.FAILED -> {
                if (_state.value == CallState.IN_CALL) enterReconnecting()
                else if (pcState == PeerConnection.PeerConnectionState.FAILED) {
                    // Pre-IN_CALL FAILED is a genuine setup failure (ICE
                    // never completed). Still end, but log loudly so tests
                    // can tell this apart from a user-initiated hang-up.
                    Log.e(TAG, "PC FAILED before IN_CALL — tearing down call $callId")
                    doEndCall(CallEndReason.NETWORK, emitToServer = true)
                }
            }
            else -> Unit
        }
    }

    private fun enterReconnecting() {
        if (_state.value == CallState.RECONNECTING) return
        _state.value = CallState.RECONNECTING
        CallSoundPlayer.playReconnect(appContext)
        // Fix [E]: kick an explicit ICE restart instead of waiting for the
        // native stack to notice. On Vietnamese mobile carriers (Viettel,
        // Mobifone) the IPv6 → IPv4 handoff during network change doesn't
        // always trigger automatic ICE restart; the PeerConnection sits in
        // DISCONNECTED until the 10s budget expires and we give up. Calling
        // restartIce() forces fresh ICE gathering + a new offer/answer to
        // re-converge candidates, which usually completes in 1-3s.
        try { peerConnection?.restartIce() } catch (e: Exception) {
            Log.e(TAG, "restartIce failed", e)
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectBudgetMs)
            if (_state.value == CallState.RECONNECTING) {
                doEndCall(CallEndReason.NETWORK, emitToServer = true)
            }
        }
    }

    private fun captureLocalMedia(isVideo: Boolean) {
        val factory = peerConnectionFactory ?: return
        if (localAudioTrack != null) return  // already captured

        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("audio0", audioSource).apply {
            setEnabled(!_micMuted.value)
        }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }

        if (isVideo && hasCameraPermission()) {
            try {
                val capturer = createCameraCapturer()
                if (capturer != null) {
                    videoCapturer = capturer
                    val helper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
                    val videoSource = factory.createVideoSource(capturer.isScreencast)
                    capturer.initialize(helper, appContext, videoSource.capturerObserver)
                    capturer.startCapture(640, 480, 30)
                    localVideoTrack = factory.createVideoTrack("video0", videoSource).apply {
                        setEnabled(_cameraOn.value)
                    }
                    localVideoTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera not available", e)
            }
        }
        try { localVideoTrack?.addSink(localRenderer) } catch (_: Exception) {}
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        enumerator.deviceNames.forEach { name ->
            if (enumerator.isFrontFacing(name)) {
                isFrontCamera = true
                return enumerator.createCapturer(name, null)
            }
        }
        enumerator.deviceNames.forEach { name ->
            isFrontCamera = false
            return enumerator.createCapturer(name, null)
        }
        return null
    }

    private fun createAndBufferOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                if (emitWhenOfferReady) {
                    emitOffer(sdp); emitWhenOfferReady = false
                } else {
                    bufferedOffer = sdp
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { Log.e(TAG, "Create offer failed: $error") }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private fun sendBufferedOffer() {
        val sdp = bufferedOffer
        if (sdp != null) {
            emitOffer(sdp); bufferedOffer = null
        } else {
            emitWhenOfferReady = true
        }
    }

    private fun processPendingOffer() {
        val sdp = pendingInboundOffer ?: return
        pendingInboundOffer = null
        applyRemoteOffer(sdp)
    }

    private fun applyRemoteOffer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushInboundCandidates()
                createAnswer()
            }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { Log.e(TAG, "setRemote(offer) fail: $error") }
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                // setLocalDescription is async — emit the answer AND flush
                // the deferred call_accept only after it lands, so the
                // caller's follow-up signals can't race our local state.
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        socket.emit("call_signal", JSONObject().apply {
                            put("call_id", callId)
                            put("signal", JSONObject().apply {
                                put("type", "answer")
                                put("sdp", sdp.description)
                            })
                        })
                        // Fix [C]: now that the answer is set locally, it's
                        // safe to tell the server we accepted. Any buffered
                        // ICE from caller that arrives after this point will
                        // find remoteDescriptionSet=true (done in onSetSuccess
                        // of setRemoteDescription earlier).
                        runOnMain { flushAcceptEmit() }
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocal(answer) fail: $error")
                    }
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { Log.e(TAG, "Create answer failed: $error") }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    private fun emitOffer(sdp: SessionDescription) {
        socket.emit("call_signal", JSONObject().apply {
            put("call_id", callId)
            put("signal", JSONObject().apply {
                put("type", "offer")
                put("sdp", sdp.description)
            })
        })
    }

    private fun emitCandidate(cid: String, candidate: IceCandidate) {
        socket.emit("call_signal", JSONObject().apply {
            put("call_id", cid)
            put("signal", JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        })
    }

    private fun flushOutboundCandidates() {
        val cid = callId ?: return
        if (cid.isEmpty()) return
        val toSend = synchronized(outboundPendingCandidates) {
            val copy = outboundPendingCandidates.toList()
            outboundPendingCandidates.clear()
            copy
        }
        toSend.forEach { emitCandidate(cid, it) }
    }

    private fun flushInboundCandidates() {
        val pc = peerConnection ?: return
        val toApply = synchronized(inboundPendingCandidates) {
            val copy = inboundPendingCandidates.toList()
            inboundPendingCandidates.clear()
            copy
        }
        toApply.forEach { pc.addIceCandidate(it) }
    }

    // ══════════════════════════════════════════════════════════════
    //  SIGNALING — listen for server events
    // ══════════════════════════════════════════════════════════════
    private fun ensureSignalListener() {
        if (signalListenerRegistered) return
        signalListenerRegistered = true

        // Callee-side: server pushes call_incoming when someone calls us. In V1
        // this was wired up in MainActivity; in V2 it belongs here so the engine
        // is self-contained. Without this, the callee never sees the call at all
        // (test 1 regression — "bên kia không đổ chuông, không hiện màn hình").
        socket.on("call_incoming") { args ->
            try {
                val data = args[0] as JSONObject
                runOnMain { handleIncomingInternal(data) }
            } catch (e: Exception) { Log.e(TAG, "call_incoming error", e) }
        }

        socket.on("call_signaling") { args ->
            try {
                val d = args[0] as JSONObject
                val cid = d.optString("call_id")
                runOnMain {
                    if (cid.isNotEmpty() && callId != cid && _state.value == CallState.INIT) {
                        // Set callId + flush atomically under the same monitor
                        // that onIceCandidate uses, so a WebRTC-thread candidate
                        // arriving in the same window can't slip into the
                        // buffer between our set and our flush. JVM monitors
                        // are reentrant, so flushOutboundCandidates' inner
                        // synchronized() block is fine.
                        synchronized(outboundPendingCandidates) {
                            callId = cid
                            flushOutboundCandidates()
                        }
                        _currentCall.value = _currentCall.value?.copy(callId = cid)
                        _state.value = CallState.CONNECTING
                        sendBufferedOffer()
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "call_signaling error", e) }
        }

        socket.on("call_ringing") { args ->
            try {
                runOnMain {
                    if (_state.value == CallState.CONNECTING) {
                        _state.value = CallState.RINGING
                        CallSoundPlayer.startRingback(appContext)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "call_ringing error", e) }
        }

        socket.on("call_accepted") { args ->
            try {
                runOnMain {
                    if (_state.value == CallState.RINGING || _state.value == CallState.CONNECTING) {
                        _state.value = CallState.ACCEPTED
                        CallSoundPlayer.stopAllCallerTones()
                        // Caller already captured media + sent offer; nothing
                        // else to do here. IN_CALL transitions on ICE CONNECTED.
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "call_accepted error", e) }
        }

        socket.on("call_signal") { args ->
            try {
                val data = args[0] as JSONObject
                val signal = data.getJSONObject("signal")
                runOnMain { handleRemoteSignal(signal) }
            } catch (e: Exception) { Log.e(TAG, "call_signal error", e) }
        }

        socket.on("call_ended") { args ->
            try {
                val d = args[0] as JSONObject
                val reason = CallEndReason.fromWire(d.optString("reason"))
                val cid = d.optString("call_id")
                Log.w(TAG, "SOCKET call_ended received: callId=$cid reason=$reason localState=${_state.value}")
                runOnMain { doEndCall(reason, emitToServer = false) }
            } catch (e: Exception) { Log.e(TAG, "call_ended error", e) }
        }

        socket.on("call_dismiss") { args ->
            try {
                val d = args[0] as JSONObject
                val cid = d.optString("call_id")
                Log.w(TAG, "SOCKET call_dismiss received: callId=$cid localCallId=$callId localState=${_state.value}")
                runOnMain {
                    // Only dismiss if we're still in the ringing phase. Past
                    // RINGING (user already accepted) this dismiss is a stale
                    // echo for another device of the same account; ignoring
                    // it prevents the "accept → auto-end" regression.
                    if (callId == cid && _state.value == CallState.RINGING) {
                        doEndCall(CallEndReason.ANSWERED_ELSEWHERE, emitToServer = false)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "call_dismiss error", e) }
        }

        socket.on("call_error") { args ->
            try {
                val d = args[0] as JSONObject
                val reason = d.optString("reason")
                runOnMain {
                    val end = when (reason) {
                        "callee_busy" -> CallEndReason.BUSY
                        else -> CallEndReason.ENDED
                    }
                    // doEndCall's BUSY branch already plays the busy tone
                    // (caller-side check is handled there via isOutgoing);
                    // don't double-play here.
                    doEndCall(end, emitToServer = false)
                }
            } catch (e: Exception) { Log.e(TAG, "call_error error", e) }
        }
    }

    private fun handleRemoteSignal(signal: JSONObject) {
        when {
            signal.optString("type") == "offer" -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, signal.getString("sdp"))
                if (localAudioTrack == null) {
                    pendingInboundOffer = sdp  // process after Accept
                } else {
                    applyRemoteOffer(sdp)
                }
            }
            signal.optString("type") == "answer" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, signal.getString("sdp"))
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        remoteDescriptionSet = true
                        flushInboundCandidates()
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setRemote(answer) fail: $error") }
                }, sdp)
            }
            signal.has("candidate") -> {
                val candidate = IceCandidate(
                    signal.getString("sdpMid"),
                    signal.getInt("sdpMLineIndex"),
                    signal.getString("candidate"),
                )
                if (remoteDescriptionSet) {
                    peerConnection?.addIceCandidate(candidate)
                } else {
                    synchronized(inboundPendingCandidates) { inboundPendingCandidates.add(candidate) }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  END + CLEANUP
    // ══════════════════════════════════════════════════════════════
    private fun doEndCall(reason: CallEndReason, emitToServer: Boolean) {
        // Stack trace in the log lets us see WHO ended the call when a test
        // reports unexpected termination. Element [3] skips the log call +
        // doEndCall + the lambda wrapper to land on the real invoker.
        val caller = try {
            Throwable().stackTrace.getOrNull(3)?.toString() ?: "unknown"
        } catch (_: Exception) { "unknown" }
        Log.w(TAG, "doEndCall: reason=$reason emit=$emitToServer state=${_state.value} callId=$callId caller=$caller")
        if (cleanupInProgress) {
            Log.d(TAG, "doEndCall skipped — cleanup already in progress")
            return
        }
        // Already torn down by an earlier doEndCall (e.g. user tapped End,
        // the server echoes call_ended back → this same method fires again).
        // Without this guard the end chime played twice ("âm thanh hang up
        // được chạy 2 lần").
        if (peerConnection == null && callId == null) {
            Log.d(TAG, "doEndCall skipped — already torn down")
            return
        }
        cleanupInProgress = true
        try {
            if (emitToServer) {
                val cid = callId
                if (cid != null) {
                    socket.emit("call_end", JSONObject().apply {
                        put("call_id", cid)
                        put("reason", reason.wire)
                    })
                }
            }

            // Capture pre-cleanup state so chime-eligibility rules can
            // reason about what phase the call was in. Rules (user spec
            // 2026-04-20, §9.12):
            //   R2: caller-side chime ONLY if caller cancelled while still
            //       ringing / connecting (i.e. call never became active).
            //   R3: both-sides chime when an ACTIVE call ends normally.
            // Anything else (reject, no_answer, busy, dismiss) → no chime.
            // Plus R1: earpiece-only route. No earpiece → skip entirely.
            val prevState = _state.value
            val isOutgoing = _currentCall.value?.isOutgoing == true
            val wasConnected =
                prevState == CallState.IN_CALL || prevState == CallState.RECONNECTING
            val callerCancelledRinging = isOutgoing &&
                reason == CallEndReason.ENDED &&
                (prevState == CallState.INIT ||
                 prevState == CallState.CONNECTING ||
                 prevState == CallState.RINGING)
            val shouldPlayEnd = wasConnected || callerCancelledRinging
            val earpieceAvailable = audio?.hasEarpiece() == true

            // Stop all sounds first.
            CallSoundPlayer.stopIncomingRingtone()
            CallSoundPlayer.stopAllCallerTones()
            // Earpiece routing: force speaker OFF while AudioManager is
            // still in MODE_IN_COMMUNICATION so USAGE_VOICE_COMMUNICATION_SIGNALLING
            // lands on the earpiece stream rather than the ringer stream
            // (= external speaker, which is what user reported as loud/noisy).
            audio?.setSpeaker(false)
            when (reason) {
                CallEndReason.BUSY -> {
                    // Caller-side only — the callee who was already in a
                    // call doesn't need the busy tone on their own device.
                    if (isOutgoing) CallSoundPlayer.playBusy(appContext)
                }
                CallEndReason.REJECTED -> {
                    // Rejection should play busy_tone on the caller's
                    // device so they hear an audible "call refused" cue.
                    // Callee tapped Từ chối themselves — they don't need
                    // it on their side. Prior behaviour was silent both
                    // sides, which the user flagged as missing feedback.
                    if (isOutgoing) CallSoundPlayer.playBusy(appContext)
                }
                CallEndReason.ANSWERED_ELSEWHERE -> Unit  // silent — another device took it
                else -> {
                    if (shouldPlayEnd && earpieceAvailable) {
                        CallSoundPlayer.playEnd(appContext)
                    } else {
                        Log.d(TAG, "End chime skipped: shouldPlayEnd=$shouldPlayEnd earpiece=$earpieceAvailable prevState=$prevState isOutgoing=$isOutgoing reason=$reason")
                    }
                }
            }
            CallSoundPlayer.vibrateTick(appContext, 40L)

            reconnectJob?.cancel(); reconnectJob = null

            // Release WebRTC resources
            try { remoteVideoTrack?.removeSink(remoteRenderer) } catch (_: Exception) {}
            try { localVideoTrack?.removeSink(localRenderer) } catch (_: Exception) {}
            try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}
            try { localVideoTrack?.setEnabled(false) } catch (_: Exception) {}
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            try { videoCapturer?.dispose() } catch (_: Exception) {}
            videoCapturer = null
            try { peerConnection?.close() } catch (_: Exception) {}
            peerConnection = null
            localAudioTrack = null
            localVideoTrack = null
            remoteVideoTrack = null
            remoteAudioTrack = null
            resetBuffers()
            audio?.exitCall()
            try { remoteRenderer?.release() } catch (_: Exception) {}
            try { localRenderer?.release() } catch (_: Exception) {}
            remoteRenderer = null
            localRenderer = null

            // State + UI
            callId = null
            _state.value = when (reason) {
                CallEndReason.REJECTED -> CallState.REJECTED
                CallEndReason.BUSY -> CallState.BUSY
                CallEndReason.NO_ANSWER -> CallState.NO_ANSWER
                CallEndReason.NETWORK -> CallState.NETWORK
                else -> CallState.ENDED
            }
            _micMuted.value = false
            _cameraOn.value = true
            _speakerOn.value = false
            _hasRemoteMedia.value = false

            stopForegroundService()

            // Emit the end reason for transient UI (toast/snackbar) BEFORE
            // flipping to IDLE. The overlay dismisses instantly on IDLE so
            // observers can't read the terminal state anymore.
            //
            // Gate per-side so the toast addresses the right audience:
            //   REJECTED / BUSY / NO_ANSWER → caller only (the one whose
            //     call didn't go through; the other side triggered the
            //     state themselves and doesn't need a reminder).
            //   ANSWERED_ELSEWHERE → callee's OTHER device (the one that
            //     didn't accept), so it's never isOutgoing.
            //   NETWORK → both sides lose the call, both want to know.
            //   ENDED / other → silent (the user tapped End themselves).
            val shouldEmitEvent = when (reason) {
                CallEndReason.REJECTED,
                CallEndReason.BUSY,
                CallEndReason.NO_ANSWER -> isOutgoing
                CallEndReason.ANSWERED_ELSEWHERE -> !isOutgoing
                CallEndReason.NETWORK -> true
                else -> false
            }
            if (shouldEmitEvent) _endEvents.tryEmit(reason)

            // Dismiss the call overlay immediately. Previous code held the
            // terminal state for 1200ms so the user saw "Cuộc gọi kết thúc"
            // / "Đã từ chối" briefly, but user spec 2026-04-20 is "tắt màn
            // hình cuộc gọi đi luôn" — close it right away and fall back to
            // the underlying screen (or lock screen if launched from call
            // notification — MainActivity.finishAndRemoveTask handles that).
            _state.value = CallState.IDLE
            _currentCall.value = null
        } finally {
            cleanupInProgress = false
            lastCleanupAtMs = System.currentTimeMillis()
        }
    }

    private fun resetBuffers() {
        bufferedOffer = null
        emitWhenOfferReady = false
        pendingInboundOffer = null
        remoteDescriptionSet = false
        pendingAcceptEmit = false
        // Clear the duration stamp for the NEW call. Previous call's final
        // displayed duration has already been rendered in its terminal-state
        // window; by the time we reach a fresh initiateCall / incoming, we
        // want the counter ticking from 0 again.
        connectedAtMs = 0L
        synchronized(outboundPendingCandidates) { outboundPendingCandidates.clear() }
        synchronized(inboundPendingCandidates) { inboundPendingCandidates.clear() }
    }

    // ══════════════════════════════════════════════════════════════
    //  FOREGROUND SERVICE
    // ══════════════════════════════════════════════════════════════
    private fun startForegroundService(stateText: String) {
        val call = _currentCall.value ?: return
        CallForegroundService.start(
            appContext,
            peerName = call.peerName,
            stateText = stateText,
            isVideo = call.type == CallType.VIDEO,
            roomId = call.roomId,
        )
    }

    private fun stopForegroundService() {
        if (::appContext.isInitialized) CallForegroundService.stop(appContext)
    }

    // ══════════════════════════════════════════════════════════════
    //  ICE config
    // ══════════════════════════════════════════════════════════════
    private fun buildIceServers(): List<PeerConnection.IceServer> {
        cachedIceServers?.let { return it }
        // Static fallback — replaced in a follow-up turn by a call to
        // /api/v1/calls/init.php during init(). Keeping fallback here so
        // calls still work if the init fetch hasn't landed yet.
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:103.170.123.106:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:103.170.123.106:3478")
                .setUsername("9chat")
                .setPassword("9chat2026turn")
                .createIceServer(),
            PeerConnection.IceServer.builder("turns:103.170.123.106:5349")
                .setUsername("9chat")
                .setPassword("9chat2026turn")
                .createIceServer(),
        )
        cachedIceServers = servers
        return servers
    }

    /** Called by CallApi/Repository after fetching /api/v1/calls/init.php. */
    fun updateIceServers(servers: List<PeerConnection.IceServer>) {
        cachedIceServers = servers
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════
    private fun runOnMain(block: () -> Unit) {
        if (isMainThread()) block() else mainHandler.post(block)
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun hasRequiredPermissions(needsCamera: Boolean): Boolean {
        val micOk = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!micOk) return false
        return !needsCamera || hasCameraPermission()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) { Log.e("SDP", "Failure: $error") }
        override fun onSetFailure(error: String) { Log.e("SDP", "Set failure: $error") }
    }
}
