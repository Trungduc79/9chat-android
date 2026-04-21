package vn.chat9.app.data.socket

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import vn.chat9.app.BuildConfig

class ChatSocket(private val tokenProvider: () -> String) {

    private var socket: Socket? = null
    private val listeners = mutableMapOf<String, MutableList<(Array<Any>) -> Unit>>()

    fun connect() {
        // If socket exists, just (re)connect it — don't create a new instance.
        // Creating a new instance while another is mid-handshake orphans queued emits.
        if (socket != null) {
            if (socket?.connected() != true) socket?.connect()
            return
        }

        val options = IO.Options().apply {
            auth = mapOf("token" to tokenProvider())
            path = "/socket.io/"
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionAttempts = 10
            reconnectionDelay = 2000
        }
        socket = IO.socket(BuildConfig.SOCKET_URL, options)

        listeners.forEach { (event, listenerList) ->
            listenerList.forEach { cb ->
                socket?.on(event) { cb(it) }
            }
        }

        socket?.connect()
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }

    fun on(event: String, listener: (Array<Any>) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
        socket?.on(event) { listener(it) }
    }

    fun off(event: String, listener: (Array<Any>) -> Unit) {
        listeners[event]?.remove(listener)
        // Socket.IO java doesn't support removing specific listener easily,
        // so we clear all and re-register remaining
        socket?.off(event)
        listeners[event]?.forEach { cb ->
            socket?.on(event) { cb(it) }
        }
    }

    fun offAll(event: String) {
        listeners.remove(event)
        socket?.off(event)
    }

    fun emit(event: String, data: JSONObject) {
        val connected = socket?.connected() == true
        android.util.Log.d("ChatSocket", "emit($event) connected=$connected data=${data.toString().take(200)}")
        socket?.emit(event, data)
    }

    fun sendMessage(
        roomId: Int, type: String, content: String?,
        fileUrl: String? = null, fileName: String? = null,
        fileSize: Long? = null, replyTo: Int? = null
    ) {
        emit("message", JSONObject().apply {
            put("room_id", roomId)
            put("type", type)
            put("content", content ?: "")
            fileUrl?.let { put("file_url", it) }
            fileName?.let { put("file_name", it) }
            fileSize?.let { put("file_size", it) }
            replyTo?.let { put("reply_to", it) }
        })
    }

    fun switchRoom(roomId: Int) {
        emit("switch_room", JSONObject().put("room_id", roomId))
    }

    fun sendTyping(roomId: Int) {
        emit("typing", JSONObject().put("room_id", roomId))
    }

    fun stopTyping(roomId: Int) {
        emit("stop_typing", JSONObject().put("room_id", roomId))
    }

    fun markSeen(roomId: Int, messageId: Int) {
        emit("message_seen", JSONObject().apply {
            put("room_id", roomId)
            put("message_id", messageId)
        })
    }

    fun reactMessage(messageId: Int, reactionType: String) {
        emit("react_message", JSONObject().apply {
            put("message_id", messageId)
            put("reaction_type", reactionType)
        })
    }

    val isConnected: Boolean get() = socket?.connected() == true
}
