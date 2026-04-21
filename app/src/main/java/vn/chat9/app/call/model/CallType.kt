package vn.chat9.app.call.model

enum class CallType(val wire: String) {
    AUDIO("voice"),   // legacy wire value is "voice"
    VIDEO("video");

    companion object {
        fun fromWire(s: String?): CallType = when (s) {
            "video" -> VIDEO
            else -> AUDIO
        }
    }
}
