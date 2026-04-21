package vn.chat9.app.data.model

data class PushSubscribeRequest(
    val endpoint: String,
    val keys: PushKeys
)

data class PushKeys(
    val p256dh: String,
    val auth: String
)
