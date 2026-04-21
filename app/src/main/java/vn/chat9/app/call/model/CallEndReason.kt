package vn.chat9.app.call.model

/**
 * Wire-compatible with the backend's `reason` field on call_ended.
 * Used both for local decisions (which tone to play, which notification
 * to post) and for emitting call_end back to the server.
 */
enum class CallEndReason(val wire: String) {
    ENDED("ended"),
    REJECTED("rejected"),
    BUSY("busy"),
    NO_ANSWER("no_answer"),
    NETWORK("network"),
    ANSWERED_ELSEWHERE("answered_elsewhere");

    companion object {
        fun fromWire(s: String?): CallEndReason = when (s) {
            "rejected" -> REJECTED
            "busy" -> BUSY
            "no_answer" -> NO_ANSWER
            "network" -> NETWORK
            "answered_elsewhere" -> ANSWERED_ELSEWHERE
            else -> ENDED
        }
    }
}
