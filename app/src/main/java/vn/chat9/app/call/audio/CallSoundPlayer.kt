package vn.chat9.app.call.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import vn.chat9.app.R

/**
 * All call-phase sounds + vibration in one place. Successor to
 * service/RingtonePlayer.kt (kept alive during Phase 2 for FCMService
 * fallback; deleted in Phase 4).
 *
 * Design notes:
 *
 * 1. Long looping tones (incoming ringtone, outgoing connecting/ringback)
 *    use MediaPlayer because SoundPool tops out at ~1 MB samples and
 *    isn't great with looping MP3s.
 *
 * 2. Short one-shot cues (end chime, busy tone, reconnect beep) use
 *    SoundPool because MediaPlayer.prepareAsync costs ~100–300ms, which
 *    arrived *after* the CallScreen had already dismissed — the end
 *    chime was audibly late ("âm thanh gác máy chạy khá trễ"). SoundPool
 *    decodes into RAM up front and plays in <10ms.
 *
 * 3. [preloadAll] is called from App.onCreate so the very first end chime
 *    doesn't hit the cold-load fallback path.
 *
 * 4. [start] is guarded by an `active` flag (NOT MediaPlayer.isPlaying,
 *    which reads false during prepareAsync — that false was causing a
 *    second start() to wipe the in-progress vibration).
 */
object CallSoundPlayer {
    private const val TAG = "CallSoundPlayer"

    // ── Long loops (MediaPlayer) ──────────────────────────────────
    private var incomingPlayer: MediaPlayer? = null
    private var connectingPlayer: MediaPlayer? = null
    private var ringbackPlayer: MediaPlayer? = null
    @Volatile private var incomingActive = false

    // ── Short one-shots (SoundPool, preloaded) ────────────────────
    private var soundPool: SoundPool? = null
    private var endSoundId: Int = 0
    private var busySoundId: Int = 0
    private var reconnectSoundId: Int = 0
    private val loadedIds = mutableSetOf<Int>()

    // ── Vibration ─────────────────────────────────────────────────
    private var vibrator: Vibrator? = null

    // ══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════
    @Synchronized
    fun preloadAll(context: Context) {
        ensureSoundPool(context)
    }

    @Synchronized
    private fun ensureSoundPool(context: Context) {
        if (soundPool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attrs)
            .build()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loadedIds) { loadedIds.add(sampleId) }
        }
        endSoundId = pool.load(context, R.raw.call_end, 1)
        busySoundId = pool.load(context, R.raw.busy_tone, 1)
        reconnectSoundId = pool.load(context, R.raw.reconnect_beep, 1)
        soundPool = pool
        Log.d(TAG, "SoundPool preloaded (end/busy/reconnect)")
    }

    // ══════════════════════════════════════════════════════════════
    //  Incoming ringtone (callee-side)
    // ══════════════════════════════════════════════════════════════
    /**
     * Start callee's incoming-call ringtone.
     *
     * @param vibrate3 If true, fire a 3-burst non-repeating vibration at
     *                 the start. Set for locked-screen / background. In
     *                 foreground we rely on the UI instead — vibration
     *                 would just be annoying.
     */
    @Synchronized
    fun startIncomingRingtone(context: Context, vibrate3: Boolean) {
        if (incomingActive) return
        incomingActive = true
        try {
            val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.zingtone}")
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            incomingPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            if (vibrate3) vibrateThreeBursts(context)
        } catch (e: Exception) {
            Log.e(TAG, "startIncomingRingtone failed", e)
            incomingActive = false
        }
    }

    @Synchronized
    fun stopIncomingRingtone() {
        incomingActive = false
        stopPlayer(incomingPlayer)
        incomingPlayer = null
        cancelVibration()
    }

    // ══════════════════════════════════════════════════════════════
    //  Caller-side tones
    // ══════════════════════════════════════════════════════════════
    /**
     * Slow single-beep loop from tap Gọi until server confirms ringing.
     * Transitions to [startRingback] once `call_ringing` arrives.
     */
    @Synchronized
    fun startConnecting(context: Context) {
        startLoopTone(
            context, R.raw.connecting_tone,
            slot = { connectingPlayer = it },
            getCurrent = { connectingPlayer },
            signaling = true
        )
    }
    @Synchronized fun stopConnecting() { stopPlayer(connectingPlayer); connectingPlayer = null }

    /**
     * "Callee's phone is ringing" loop for the caller. Replaces the
     * connecting tone once call_ringing arrives.
     */
    @Synchronized
    fun startRingback(context: Context) {
        stopConnecting()
        startLoopTone(
            context, R.raw.ringback_tone,
            slot = { ringbackPlayer = it },
            getCurrent = { ringbackPlayer },
            signaling = true
        )
    }
    @Synchronized fun stopRingback() { stopPlayer(ringbackPlayer); ringbackPlayer = null }

    /** Stop every caller-phase loop tone. Use at state transitions. */
    @Synchronized fun stopAllCallerTones() {
        stopConnecting()
        stopRingback()
    }

    // ══════════════════════════════════════════════════════════════
    //  Short one-shots (SoundPool)
    // ══════════════════════════════════════════════════════════════
    /** End chime. Volume 0.5 — it's a confirmation, not a ring. */
    fun playEnd(context: Context) = playSample(context, endSoundId, 0.5f)

    /** Busy tone — fast-bleep played when callee is in another call. */
    fun playBusy(context: Context) = playSample(context, busySoundId, 1f)

    /** Short blip during ICE reconnect. */
    fun playReconnect(context: Context) = playSample(context, reconnectSoundId, 0.6f)

    // ══════════════════════════════════════════════════════════════
    //  Vibration
    // ══════════════════════════════════════════════════════════════
    /**
     * 3-burst vibration — start of incoming call (locked screen) and
     * when the caller cancels before pickup.
     * Pattern: ~450ms on / 500ms off × 3, non-repeating.
     */
    fun vibrateThreeBursts(context: Context) {
        try {
            val vib = getVibrator(context) ?: return
            vibrator = vib
            val pattern = longArrayOf(0, 450, 500, 450, 500, 450)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_RINGTONE)
                    .build()
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrateThreeBursts failed", e)
        }
    }

    /**
     * Single haptic tick. 40ms for hang-up, ~150ms for connect-success.
     */
    fun vibrateTick(context: Context, durationMs: Long = 40L) {
        try {
            val vib = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    //  Internal helpers
    // ══════════════════════════════════════════════════════════════
    private fun signalingAttrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun startLoopTone(
        context: Context,
        resId: Int,
        slot: (MediaPlayer?) -> Unit,
        getCurrent: () -> MediaPlayer?,
        signaling: Boolean,
    ) {
        if (getCurrent()?.isPlaying == true) return
        try { getCurrent()?.release() } catch (_: Exception) {}
        slot(null)
        try {
            val uri = Uri.parse("android.resource://${context.packageName}/$resId")
            val player = MediaPlayer().apply {
                setAudioAttributes(signalingAttrs())
                setDataSource(context, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            slot(player)
        } catch (e: Exception) {
            Log.e(TAG, "startLoopTone failed for res=$resId", e)
        }
    }

    private fun stopPlayer(player: MediaPlayer?) {
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
    }

    private fun playSample(context: Context, id: Int, volume: Float) {
        ensureSoundPool(context)
        val pool = soundPool ?: return
        val isLoaded = synchronized(loadedIds) { id in loadedIds }
        if (isLoaded) {
            pool.play(id, volume, volume, 1, 0, 1f)
        } else {
            playFallbackOneShot(context, id, volume)
        }
    }

    private fun playFallbackOneShot(context: Context, sampleId: Int, volume: Float) {
        val resId = when (sampleId) {
            endSoundId -> R.raw.call_end
            busySoundId -> R.raw.busy_tone
            reconnectSoundId -> R.raw.reconnect_beep
            else -> return
        }
        try {
            val uri = Uri.parse("android.resource://${context.packageName}/$resId")
            MediaPlayer().apply {
                setAudioAttributes(signalingAttrs())
                setDataSource(context, uri)
                isLooping = false
                setVolume(volume, volume)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback one-shot failed", e)
        }
    }

    private fun cancelVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) { null }
    }
}
