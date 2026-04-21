package vn.chat9.app.call.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Centralised AudioManager wrapper.
 *
 * Previous implementation scattered `AudioManager.mode = ...` and
 * `isSpeakerphoneOn = ...` writes across CallManager, FCMService,
 * CallScreen, and the ringtone player. That cost us foot-gun #5 —
 * callee prewarm set IN_COMMUNICATION mode, which hijacked the ringtone
 * to the earpiece instead of the external speaker.
 *
 * This class is the single writer. All call sites go through
 * enterRingingPhase / enterInCallPhase / exitCallPhase — never touch
 * AudioManager directly.
 *
 * Phase mapping (see CLAUDE-CALL-SYSTEM-V2.md §2.4):
 *
 *   Phase               mode                 speaker      mic
 *   ─────────────────── ──────────────────── ──────────── ────────
 *   IDLE                NORMAL               false        false
 *   INCOMING RINGING    NORMAL               —            false
 *   OUTGOING RINGING    IN_COMMUNICATION     = isVideo    false
 *   IN_CALL (audio)     IN_COMMUNICATION     user-choice  user-choice
 *   IN_CALL (video)     IN_COMMUNICATION     true         user-choice
 *   POST-END            NORMAL               false        false
 */
class CallAudioRouter(context: Context) {
    private val appContext = context.applicationContext
    private val am: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeaker: Boolean = false

    /**
     * True if this device has a built-in earpiece. Used by the end-chime rule
     * — the tone should only play on earpiece (per-user spec, 2026-04-20).
     * Tablets without an earpiece return false → chime is skipped entirely.
     *
     * API 23+ uses `AudioManager.getDevices(...)` to enumerate outputs and
     * look for TYPE_BUILTIN_EARPIECE. Older devices fall back to the
     * telephony-feature heuristic (phones have it, tablets usually don't).
     */
    fun hasEarpiece(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return try {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            } catch (e: Exception) {
                Log.w(TAG, "hasEarpiece via getDevices failed, fallback to feature check", e)
                appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            }
        }
        return appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    /**
     * Callee incoming ringing. Keep MODE_NORMAL so the ringtone routes
     * to the external speaker stream, not the earpiece. Do NOT capture
     * mic here — that's Accept's job.
     */
    fun enterIncomingRinging() {
        try {
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false
            Log.d(TAG, "enterIncomingRinging: NORMAL mode, speaker off")
        } catch (e: Exception) {
            Log.e(TAG, "enterIncomingRinging failed", e)
        }
    }

    /**
     * Caller outgoing ringing. We already own the mic here (getLocalMedia
     * ran in prepareLocalSide). Switch to IN_COMMUNICATION so the ringback
     * tone routes correctly.
     */
    fun enterOutgoingRinging(isVideo: Boolean) {
        try {
            savedMode = am.mode
            savedSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = isVideo
            am.isMicrophoneMute = false
            Log.d(TAG, "enterOutgoingRinging: IN_COMMUNICATION, speaker=$isVideo")
        } catch (e: Exception) {
            Log.e(TAG, "enterOutgoingRinging failed", e)
        }
    }

    /**
     * Transition to active call. Invoked from acceptCall (callee) or
     * from call_accepted (caller). Safe to call multiple times.
     */
    fun enterInCall(isVideo: Boolean) {
        try {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                savedMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            // Video defaults to speakerphone; audio respects prior user toggle
            if (isVideo) am.isSpeakerphoneOn = true
            am.isMicrophoneMute = false
            Log.d(TAG, "enterInCall: video=$isVideo, speaker=${am.isSpeakerphoneOn}")
        } catch (e: Exception) {
            Log.e(TAG, "enterInCall failed", e)
        }
    }

    /**
     * User-toggled speaker during a call (audio path button).
     */
    fun setSpeaker(on: Boolean) {
        try { am.isSpeakerphoneOn = on } catch (_: Exception) {}
    }

    fun isSpeakerOn(): Boolean = try { am.isSpeakerphoneOn } catch (_: Exception) { false }

    /**
     * Dual-level mic mute (foot-gun #6). Track.setEnabled() alone is
     * unreliable on some OEM WebRTC builds, so we also set the OS-level
     * AudioManager flag. RtpSender.setTrack(null→track) is intentionally
     * NOT used — it permanently kills the mic on some builds.
     */
    fun setMicMute(muted: Boolean) {
        try { am.isMicrophoneMute = muted } catch (_: Exception) {}
    }

    /**
     * Restore routing to IDLE. Called in endCall cleanup.
     */
    fun exitCall() {
        try {
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false
            Log.d(TAG, "exitCall: restored NORMAL mode")
        } catch (e: Exception) {
            Log.e(TAG, "exitCall failed", e)
        }
    }

    companion object {
        private const val TAG = "CallAudioRouter"
    }
}
