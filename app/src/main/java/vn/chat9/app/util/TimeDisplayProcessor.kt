package vn.chat9.app.util

data class TimeDisplayConfig(
    val showTime: Boolean,
    val position: TimePosition,
    val style: TimeStyle
)

enum class TimePosition {
    INSIDE_BUBBLE,
    MEDIA_OVERLAY,
    BELOW_BUBBLE,
    HIDDEN
}

enum class TimeStyle {
    NORMAL,
    OVERLAY
}

object TimeDisplayProcessor {

    private const val BATCH_WINDOW_MS = 60_000L

    fun getConfig(
        type: String,
        createdAt: String,
        userId: Int,
        nextUserId: Int?,
        nextCreatedAt: String?
    ): TimeDisplayConfig {

        if (type == "recalled") {
            return TimeDisplayConfig(false, TimePosition.HIDDEN, TimeStyle.NORMAL)
        }

        if (type == "image" || type == "video") {
            return TimeDisplayConfig(true, TimePosition.MEDIA_OVERLAY, TimeStyle.OVERLAY)
        }

        if (type == "audio") {
            return TimeDisplayConfig(
                showTime = isFinalInBatch(userId, createdAt, nextUserId, nextCreatedAt),
                position = TimePosition.BELOW_BUBBLE,
                style = TimeStyle.NORMAL
            )
        }

        if (type == "sticker") {
            return TimeDisplayConfig(false, TimePosition.HIDDEN, TimeStyle.NORMAL)
        }

        if (!isFinalInBatch(userId, createdAt, nextUserId, nextCreatedAt)) {
            return TimeDisplayConfig(false, TimePosition.HIDDEN, TimeStyle.NORMAL)
        }

        return TimeDisplayConfig(
            showTime = true,
            position = TimePosition.INSIDE_BUBBLE,
            style = TimeStyle.NORMAL
        )
    }

    fun isFinalInBatch(
        userId: Int,
        createdAt: String,
        nextUserId: Int?,
        nextCreatedAt: String?
    ): Boolean {
        if (nextUserId == null || nextCreatedAt == null) return true
        if (nextUserId != userId) return true
        val diff = DateUtils.toMillis(nextCreatedAt) - DateUtils.toMillis(createdAt)
        return diff > BATCH_WINDOW_MS
    }
}
