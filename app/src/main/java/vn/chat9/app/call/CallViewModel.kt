package vn.chat9.app.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import vn.chat9.app.call.model.CallState

/**
 * Thin adapter between the CallManager singleton (state store + side
 * effects) and the Compose UI.
 *
 * The elapsed-seconds counter is computed from `CallManager.connectedAtMs`
 * (wall clock), NOT incremented per tick. That way transient RECONNECTING
 * windows don't roll the counter back to 0 — the ticker just keeps reading
 * `now - connectedAt`, which advances linearly regardless of WebRTC state.
 *
 * User spec (2026-04-20): "Việc đếm thời gian chạy độc lập không bị ảnh
 * hưởng bởi lỗi gì. Đếm từ lúc kết nối đến lúc ngắt kết nối."
 */
class CallViewModel : ViewModel() {

    val state: StateFlow<CallState> = CallManager.state
    val currentCall = CallManager.currentCall
    val micMuted = CallManager.micMuted
    val speakerOn = CallManager.speakerOn
    val cameraOn = CallManager.cameraOn

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            state.collect { s ->
                when (s) {
                    // Any post-accept state keeps the ticker alive. Critically,
                    // RECONNECTING is included here — the user sees the counter
                    // keep advancing through a network blip, not freeze/reset.
                    CallState.ACCEPTED,
                    CallState.IN_CALL,
                    CallState.RECONNECTING -> {
                        if (tickerJob?.isActive != true) startTicker()
                    }
                    CallState.IDLE -> {
                        stopTicker()
                        _elapsedSeconds.value = 0
                    }
                    CallState.ENDED, CallState.REJECTED, CallState.BUSY,
                    CallState.NO_ANSWER, CallState.NETWORK -> {
                        // Stop ticking but KEEP the last value so the user sees
                        // the final duration in the 1.2s terminal-state window
                        // before CallManager flips state to IDLE.
                        stopTicker()
                    }
                    else -> Unit  // INIT / CONNECTING / RINGING — no counter
                }
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val start = CallManager.getConnectedAtMs()
                _elapsedSeconds.value = if (start == 0L) 0
                    else ((System.currentTimeMillis() - start) / 1000).toInt()
                        .coerceAtLeast(0)
                delay(1000)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    override fun onCleared() {
        stopTicker()
        super.onCleared()
    }

    fun accept() = CallManager.acceptCall()
    fun reject() = CallManager.rejectCall()
    fun end() = CallManager.endCall()
    fun toggleMute() = CallManager.toggleMute()
    fun toggleSpeaker() = CallManager.toggleSpeaker()
    fun toggleCamera() = CallManager.toggleCamera()
    fun switchCamera() = CallManager.switchCamera()
}
