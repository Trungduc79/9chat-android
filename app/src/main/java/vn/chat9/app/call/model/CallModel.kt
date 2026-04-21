package vn.chat9.app.call.model

/**
 * Immutable snapshot of the "other party" in the current call, plus
 * call metadata. Exposed via StateFlow<CallModel?> on CallManager.
 *
 * `isOutgoing=true` → we are the caller, peer is the callee.
 * `isOutgoing=false` → we are the callee, peer is the caller.
 */
data class CallModel(
    val callId: String,
    val peerUserId: Int,
    val peerName: String,       // alias if set, otherwise username
    val peerAvatar: String?,
    val roomId: Int,
    val type: CallType,
    val isOutgoing: Boolean,
    val startedAtMs: Long = System.currentTimeMillis(),
)
