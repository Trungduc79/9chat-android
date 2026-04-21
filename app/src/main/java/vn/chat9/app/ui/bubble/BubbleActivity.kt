package vn.chat9.app.ui.bubble

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import vn.chat9.app.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import vn.chat9.app.App
import vn.chat9.app.data.model.Friend
import vn.chat9.app.data.model.Message
import vn.chat9.app.data.model.Room
import vn.chat9.app.ui.chat.MessageBubble
import vn.chat9.app.ui.theme._9chatTheme
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.TimeDisplayConfig
import vn.chat9.app.util.TimeDisplayProcessor
import vn.chat9.app.util.TimePosition
import vn.chat9.app.util.TimeStyle
import vn.chat9.app.util.UrlUtils

/**
 * Bubble Activity — embedded in the Android Bubbles expanded container
 * (see manifest attrs: allowEmbedded, resizeableActivity, documentLaunchMode).
 *
 * Phase 2A: Header + read-only message list from REST API.
 * Phase 2B (current): Realtime socket listener + inline send (text only).
 * Phase 2C: Polish (call/video buttons, presence text, settings footer).
 * Phase 2D: Rich content (image, file, contact, location, call, reactions).
 */
class BubbleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra("room_id")?.toIntOrNull()
        if (roomId == null || roomId <= 0) {
            finish()
            return
        }
        val container = (application as App).container
        val currentUserId = container.tokenManager.user?.id
        if (!container.tokenManager.isLoggedIn || currentUserId == null) {
            finish()
            return
        }
        // Required for Compose's WindowInsets (imePadding, ime.getBottom)
        // to track the soft keyboard reliably inside the bubble container.
        // Without this, Samsung One UI bubble windows don't fire IME insets
        // updates → message list never re-scrolls when the keyboard opens.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            _9chatTheme {
                BubbleChatScreen(roomId = roomId, currentUserId = currentUserId)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleChatScreen(roomId: Int, currentUserId: Int) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val scope = rememberCoroutineScope()

    var room by remember { mutableStateOf<Room?>(null) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Initial load: room detail + last 50 messages.
    LaunchedEffect(roomId) {
        scope.launch {
            try {
                val roomRes = container.api.getRoomDetail(roomId)
                if (roomRes.success && roomRes.data != null) {
                    room = roomRes.data
                } else {
                    error = roomRes.message ?: "Không tải được thông tin phòng"
                    loading = false
                    return@launch
                }
                val msgRes = container.api.getMessages(roomId, limit = 50)
                if (msgRes.success && msgRes.data != null) {
                    // Backend returns ORDER BY id DESC. Sort ASC so oldest is at
                    // top, newest at bottom — Zalo-style. Sorting by id (not by
                    // created_at string) is robust to identical-second timestamps
                    // and avoids parsing dates on every list rebuild.
                    messages = msgRes.data.messages.sortedBy { it.id }
                } else {
                    error = msgRes.message ?: "Không tải được tin nhắn"
                }
            } catch (e: Exception) {
                error = "Lỗi: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // Realtime: connect socket (idempotent — singleton in container) + listen
    // for new messages in this room.
    DisposableEffect(roomId) {
        if (!container.socket.isConnected) container.socket.connect()
        val gson = Gson()
        val listener: (Array<Any>) -> Unit = { args ->
            try {
                val raw = args[0] as JSONObject
                val incomingRoomId = raw.optInt("room_id", -1)
                if (incomingRoomId == roomId) {
                    val msg = gson.fromJson(raw.toString(), Message::class.java)
                    messages = (messages.filter { it.id != msg.id } + msg).sortedBy { it.id }
                }
            } catch (_: Exception) { /* ignore malformed payload */ }
        }
        container.socket.on("message", listener)
        onDispose {
            container.socket.off("message", listener)
        }
    }

    // Track viewing status across the bubble's lifecycle, not just on
    // composition. Bubble collapse (tap outside the expanded view) only
    // PAUSES BubbleActivity — it stays alive in memory ready to re-expand.
    // Without lifecycle observers, switchRoom(0) would never fire on
    // collapse, server keeps `currentRoom = roomId`, and isUserViewingRoom
    // suppresses every subsequent push for this room — exactly the
    // "không hiện thông báo nữa" symptom.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(roomId, lifecycleOwner) {
        container.socket.switchRoom(roomId)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    container.socket.switchRoom(roomId)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    container.socket.switchRoom(0)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.socket.switchRoom(0)
        }
    }

    // Input state lifted here so panels (emoji / action / audio) can append
    // or mutate the same draft string the user is typing. Mirrors ChatScreen.
    var draft by remember { mutableStateOf("") }
    var showEmojiPanel by remember { mutableStateOf(false) }
    var showActionPanel by remember { mutableStateOf(false) }
    var showAudioPanel by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Exclusive-toggle helper — ChatScreen logic: opening one panel closes
    // the other two and the soft keyboard. Tapping the same button twice
    // closes its own panel.
    val togglePanel: (String) -> Unit = { which ->
        when (which) {
            "emoji" -> {
                val newState = !showEmojiPanel
                showEmojiPanel = newState; showActionPanel = false; showAudioPanel = false
                if (newState) { keyboardController?.hide(); focusManager.clearFocus() }
            }
            "action" -> {
                val newState = !showActionPanel
                showActionPanel = newState; showEmojiPanel = false; showAudioPanel = false
                if (newState) { keyboardController?.hide(); focusManager.clearFocus() }
            }
            "audio" -> {
                val newState = !showAudioPanel
                showAudioPanel = newState; showEmojiPanel = false; showActionPanel = false
                if (newState) { keyboardController?.hide(); focusManager.clearFocus() }
            }
        }
    }

    val sendText: () -> Unit = {
        val trimmed = draft.trim()
        if (trimmed.isNotEmpty()) {
            container.socket.sendMessage(roomId = roomId, type = "text", content = trimmed)
            draft = ""
        }
    }

    // Shared upload helper for image/file. Reads Uri content into bytes, caps
    // at 50MB, multipart-uploads through the files API, then emits a socket
    // message of the appropriate type. Mirrors ChatScreen's imagePicker/filePicker
    // logic so both surfaces send identical message payloads.
    var isUploading by remember { mutableStateOf(false) }
    val uploadUri: (Uri, Boolean) -> Unit = { uri, asImage ->
        scope.launch {
            isUploading = true
            try {
                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")
                    ?.use { it.statSize } ?: 0L
                if (fileSize > 50 * 1024 * 1024) {
                    android.widget.Toast.makeText(context, "File quá lớn (tối đa 50MB)", android.widget.Toast.LENGTH_SHORT).show()
                    isUploading = false
                    return@launch
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run { isUploading = false; return@launch }
                val mimeType = context.contentResolver.getType(uri)
                    ?: if (asImage) "image/jpeg" else "application/octet-stream"
                val fileName: String = if (asImage) {
                    val ext = when {
                        mimeType.contains("png") -> "png"
                        mimeType.contains("gif") -> "gif"
                        mimeType.contains("webp") -> "webp"
                        else -> "jpg"
                    }
                    "image_${System.currentTimeMillis()}.$ext"
                } else {
                    var name = "file_${System.currentTimeMillis()}"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) name = c.getString(idx)
                        }
                    }
                    name
                }
                val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)
                val roomIdBody = roomId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                val res = withContext(Dispatchers.IO) {
                    container.api.uploadFile(filePart, roomIdBody)
                }
                if (res.success && res.data != null) {
                    container.socket.sendMessage(
                        roomId = roomId,
                        type = if (asImage || mimeType.startsWith("image")) "image" else "file",
                        content = if (asImage) "" else res.data.file_name,
                        fileUrl = res.data.file_url,
                        fileName = res.data.file_name,
                        fileSize = res.data.file_size,
                    )
                } else {
                    android.widget.Toast.makeText(context, res.message ?: "Tải lên thất bại", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("Bubble", "Upload failed", e)
                android.widget.Toast.makeText(context, "Lỗi tải lên: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            isUploading = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadUri(uri, true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadUri(uri, false)
    }

    // ─── 2D.4: Voice message ─────────────────────────────────────────────
    val audioFile = remember { java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg") }
    val recorder = remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableIntStateOf(0) }

    // Tick recorder timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordingDuration++
                if (recordingDuration >= 60) break // hard cap 60s
            }
        }
    }

    val recordPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) android.widget.Toast.makeText(context, "Cần quyền ghi âm", android.widget.Toast.LENGTH_SHORT).show()
    }

    val startRecording: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            recordPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            try {
                if (audioFile.exists()) audioFile.delete()
                val mr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    android.media.MediaRecorder(context) else @Suppress("DEPRECATION") android.media.MediaRecorder()
                mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.OGG)
                    mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.OPUS)
                    mr.setAudioSamplingRate(16000)
                    mr.setAudioEncodingBitRate(32000)
                } else {
                    mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioSamplingRate(44100)
                    mr.setAudioEncodingBitRate(128000)
                }
                mr.setOutputFile(audioFile.absolutePath)
                mr.prepare()
                mr.start()
                recorder.value = mr
                isRecording = true
            } catch (e: Exception) {
                android.util.Log.e("BubbleAudio", "Start recording failed", e)
                android.widget.Toast.makeText(context, "Không thể ghi âm", android.widget.Toast.LENGTH_SHORT).show()
                recorder.value = null
                isRecording = false
            }
        }
    }

    val stopRecordingAndSend: () -> Unit = {
        try { recorder.value?.stop() } catch (_: Exception) {}
        try { recorder.value?.release() } catch (_: Exception) {}
        recorder.value = null
        val duration = recordingDuration
        isRecording = false
        if (duration < 1) {
            audioFile.delete()
            android.widget.Toast.makeText(context, "Tin nhắn quá ngắn", android.widget.Toast.LENGTH_SHORT).show()
        } else if (audioFile.exists() && audioFile.length() > 0) {
            scope.launch {
                isUploading = true
                try {
                    val bytes = audioFile.readBytes()
                    val mimeType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "audio/ogg" else "audio/mp4"
                    val ext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "ogg" else "m4a"
                    val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                    val filePart = MultipartBody.Part.createFormData("file", "voice_${System.currentTimeMillis()}.$ext", requestBody)
                    val roomIdBody = roomId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                    val res = withContext(Dispatchers.IO) { container.api.uploadFile(filePart, roomIdBody) }
                    if (res.success && res.data != null) {
                        container.socket.sendMessage(
                            roomId = roomId, type = "audio", content = "Tin nhắn thoại",
                            fileUrl = res.data.file_url, fileName = res.data.file_name, fileSize = res.data.file_size
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BubbleAudio", "Upload failed", e)
                }
                isUploading = false
                audioFile.delete()
            }
        }
    }

    val cancelRecording: () -> Unit = {
        try { recorder.value?.stop() } catch (_: Exception) {}
        try { recorder.value?.release() } catch (_: Exception) {}
        recorder.value = null
        isRecording = false
        audioFile.delete()
    }

    // ─── 2D.6: Location ──────────────────────────────────────────────────
    var locationPending by remember { mutableStateOf(false) }

    // Shared worker: given a Location, reverse-geocode via Nominatim (async,
    // fall back to coordinates if network fails) and emit the socket message.
    val emitLocation: (android.location.Location) -> Unit = { loc ->
        val lat = loc.latitude; val lng = loc.longitude
        scope.launch {
            val address = try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=18&addressdetails=1"
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "9chat-android/1.0").build()
                val res = withContext(Dispatchers.IO) { okhttp3.OkHttpClient().newCall(req).execute() }
                val json = org.json.JSONObject(res.body?.string() ?: "{}")
                json.optString("display_name", "").ifEmpty { "${"%.6f".format(lat)}, ${"%.6f".format(lng)}" }
            } catch (_: Exception) {
                "${"%.6f".format(lat)}, ${"%.6f".format(lng)}"
            }
            container.socket.sendMessage(
                roomId = roomId, type = "location",
                content = org.json.JSONObject().put("lat", lat).put("lng", lng).put("address", address).toString(),
            )
            locationPending = false
        }
    }

    val doFetchAndSendLocation: () -> Unit = {
        if (!locationPending) {
            locationPending = true
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val hasGps = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            val hasNetwork = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (!hasGps && !hasNetwork) {
                android.widget.Toast.makeText(context, "Vui lòng bật định vị", android.widget.Toast.LENGTH_SHORT).show()
                locationPending = false
            } else {
                // Fast path: prefer a recent last-known fix from EITHER provider.
                // requestSingleUpdate can take 5-15s if GPS needs a cold fix —
                // most users just moved and the cached location is < 2 min old.
                @Suppress("MissingPermission")
                val lastFix = listOfNotNull(
                    if (hasGps) lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) else null,
                    if (hasNetwork) lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) else null,
                ).maxByOrNull { it.time }
                val fresh = lastFix != null &&
                    System.currentTimeMillis() - lastFix.time < 2 * 60 * 1000L
                if (fresh) {
                    // Instant send — no GPS round-trip.
                    emitLocation(lastFix!!)
                } else {
                    android.widget.Toast.makeText(context, "Đang lấy vị trí...", android.widget.Toast.LENGTH_SHORT).show()
                    @Suppress("MissingPermission")
                    val provider = if (hasGps) android.location.LocationManager.GPS_PROVIDER else android.location.LocationManager.NETWORK_PROVIDER
                    lm.requestSingleUpdate(provider, object : android.location.LocationListener {
                        override fun onLocationChanged(loc: android.location.Location) {
                            emitLocation(loc)
                        }
                        @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {
                            android.widget.Toast.makeText(context, "Định vị đã bị tắt", android.widget.Toast.LENGTH_SHORT).show()
                            locationPending = false
                        }
                    }, android.os.Looper.getMainLooper())
                    scope.launch {
                        kotlinx.coroutines.delay(10_000)
                        if (locationPending) {
                            // Last-ditch: try stale last-known of any age rather than give up.
                            @Suppress("MissingPermission")
                            val anyLast = listOfNotNull(
                                if (hasGps) lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) else null,
                                if (hasNetwork) lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) else null,
                            ).maxByOrNull { it.time }
                            if (anyLast != null) {
                                emitLocation(anyLast)
                            } else {
                                locationPending = false
                                android.widget.Toast.makeText(context, "Hết thời gian chờ vị trí", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            doFetchAndSendLocation()
        } else {
            android.widget.Toast.makeText(context, "Cần quyền truy cập vị trí", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val sendLocation: () -> Unit = {
        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPerm) doFetchAndSendLocation()
        else locationPermLauncher.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    // ─── 2D.6: Contact picker ────────────────────────────────────────────
    var showContactPicker by remember { mutableStateOf(false) }

    // ─── 2D.5: Speech-to-text — lifted state for full-panel parity ───────
    val speechRecognizer = remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }
    var sttListening by remember { mutableStateOf(false) }
    var sttPartialText by remember { mutableStateOf("") }
    var sttHandsFree by remember { mutableStateOf(false) }
    var sttAutoSend by remember { mutableStateOf(false) }
    var audioModeText by remember { mutableStateOf(false) } // false=record, true=STT

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.value?.destroy(); speechRecognizer.value = null }
    }

    val startStt: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            recordPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            android.widget.Toast.makeText(context, "Thiết bị không hỗ trợ nhận diện giọng nói", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            speechRecognizer.value?.destroy()
            val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onResults(results: android.os.Bundle) {
                    val text = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        draft = if (draft.isBlank()) text else "$draft $text"
                    }
                    sttListening = false
                    sttPartialText = ""
                    if (sttAutoSend && draft.isNotBlank()) {
                        container.socket.sendMessage(roomId = roomId, type = "text", content = draft.trim())
                        draft = ""
                        sttAutoSend = false
                    }
                }
                override fun onPartialResults(partial: android.os.Bundle) {
                    val t = partial.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    sttPartialText = t
                }
                override fun onError(error: Int) {
                    sttListening = false
                    sttPartialText = ""
                    val msg = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận ra giọng nói"
                        android.speech.SpeechRecognizer.ERROR_NETWORK -> "Lỗi kết nối"
                        android.speech.SpeechRecognizer.ERROR_AUDIO -> "Lỗi micro"
                        else -> "Lỗi nhận dạng ($error)"
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                override fun onReadyForSpeech(p: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            sr.startListening(intent)
            speechRecognizer.value = sr
            sttListening = true
            sttPartialText = ""
        }
    }

    val stopStt: () -> Unit = {
        speechRecognizer.value?.stopListening()
        sttListening = false
        sttPartialText = ""
    }

    val cancelStt: () -> Unit = {
        speechRecognizer.value?.cancel()
        sttListening = false
        sttPartialText = ""
        sttHandsFree = false
        sttAutoSend = false
    }

    val stopSttAndSend: () -> Unit = {
        sttAutoSend = true
        sttHandsFree = false
        speechRecognizer.value?.stopListening()
    }

    // Hands-free mode (audio recording lock + review)
    var audioHandsFreeMode by remember { mutableStateOf(false) }
    var audioAmplitude by remember { mutableFloatStateOf(0f) }
    val amplitudeHistory = remember { mutableStateListOf<Float>() }
    var previewPlaying by remember { mutableStateOf(false) }
    val previewPlayer = remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    // Track amplitude during recording (replaces the simple 1s tick from 2D.4)
    LaunchedEffect(isRecording) {
        if (isRecording) {
            amplitudeHistory.clear()
            recordingDuration = 0
            while (isRecording) {
                val amp = recorder.value?.maxAmplitude?.toFloat() ?: 0f
                val normalized = (amp / 32768f).coerceIn(0f, 1f)
                audioAmplitude = normalized
                amplitudeHistory.add(normalized)
                kotlinx.coroutines.delay(100)
                if (amplitudeHistory.size % 10 == 0) recordingDuration = amplitudeHistory.size / 10
                if (recordingDuration >= 20) {
                    if (audioHandsFreeMode) {
                        try { recorder.value?.stop(); recorder.value?.release() } catch (_: Exception) {}
                        recorder.value = null
                        isRecording = false
                    } else {
                        stopRecordingAndSend()
                    }
                }
            }
            audioAmplitude = 0f
        }
    }

    // Send the recorded file (used by hands-free review's Send button)
    val sendRecordedAudioFile: () -> Unit = {
        if (audioFile.exists() && audioFile.length() > 0) {
            scope.launch {
                isUploading = true
                try {
                    val bytes = audioFile.readBytes()
                    val mimeType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "audio/ogg" else "audio/mp4"
                    val ext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "ogg" else "m4a"
                    val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                    val filePart = MultipartBody.Part.createFormData("file", "voice_${System.currentTimeMillis()}.$ext", requestBody)
                    val roomIdBody = roomId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                    val res = withContext(Dispatchers.IO) { container.api.uploadFile(filePart, roomIdBody) }
                    if (res.success && res.data != null) {
                        container.socket.sendMessage(
                            roomId = roomId, type = "audio", content = "Tin nhắn thoại",
                            fileUrl = res.data.file_url, fileName = res.data.file_name, fileSize = res.data.file_size,
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BubbleAudio", "Hands-free upload failed", e)
                }
                isUploading = false
                audioFile.delete()
                audioHandsFreeMode = false
                recordingDuration = 0
                amplitudeHistory.clear()
            }
        }
    }

    // Lifted state so the InputBar focus listener can scroll the list.
    val listState = rememberLazyListState()

    // Scroll to the newest message when the input gets focus (user about to
    // type). With reverseLayout=true, index 0 is the newest (visual bottom).
    val scrollToBottom: suspend () -> Unit = {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // Re-scroll continuously while the soft keyboard is animating up. The
    // simple isImeVisible boolean only flips once at the start, but Android
    // animates the IME in over ~150-200ms — during that animation the
    // viewport keeps shrinking and LazyColumn keeps the absolute scroll
    // position so the last message slides under the keyboard.
    //
    // Watching ime.getBottom() returns the live insets pixel value, which
    // changes throughout the animation → LaunchedEffect re-fires on every
    // frame → we keep the last item pinned to the bottom edge.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom, messages.size) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(0) // reverseLayout → 0 = newest = bottom
        }
    }

    // Open the full app in this room (used by header icon + name tap).
    val launchInApp: () -> Unit = {
        val intent = android.content.Intent(context, vn.chat9.app.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("room_id", roomId.toString())
        }
        context.startActivity(intent)
    }

    // Start a call directly. We bypass MainActivity.runAutoCallback (which
    // fetches the room + opens ChatScreen first → user briefly sees the chat
    // before the call overlay appears). Instead:
    //   1. Trigger CallManager.initiateCall right here — singleton, no Activity
    //      needed for state. CallState flips out of IDLE immediately.
    //   2. Launch MainActivity with direct_call=true so it skips the chat
    //      navigation and just hosts CallScreenHost overlay (which auto-renders
    //      because state != IDLE). User sees only the call UI, no chat flash.
    //   3. Bubble Activity stays alive in its separate task (taskAffinity=""),
    //      so when MainActivity finishAndRemoveTask after the call, the system
    //      resumes the bubble task — bubble is NOT removed.
    val launchCall: (Boolean) -> Unit = { isVideo ->
        val r = room
        val peer = r?.other_user
        if (peer == null) {
            android.widget.Toast.makeText(context, "Chưa tải xong thông tin phòng", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            val type = if (isVideo) vn.chat9.app.call.model.CallType.VIDEO
                else vn.chat9.app.call.model.CallType.AUDIO
            val started = vn.chat9.app.call.CallManager.initiateCall(
                roomId = roomId,
                calleeId = peer.id,
                calleeName = peer.displayName,
                calleeAvatar = peer.avatar,
                type = type,
            )
            if (!started) {
                android.widget.Toast.makeText(
                    context,
                    "Không thể gọi (đang bận hoặc thiếu quyền)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                val intent = android.content.Intent(context, vn.chat9.app.MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("direct_call", true)
                }
                context.startActivity(intent)
            }
        }
    }

    Surface(
        // imePadding() reserves space for the soft keyboard so the input bar
        // and the latest messages stay visible while typing. Pairs with the
        // Activity's windowSoftInputMode="adjustResize".
        modifier = Modifier.fillMaxSize().imePadding(),
        color = Color(0xFFEAF4FB) // Zalo-like pale blue chat background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BubbleHeader(
                room = room,
                onOpenInApp = launchInApp,
                onAudioCall = { launchCall(false) },
                onVideoCall = { launchCall(true) }
            )

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                else -> MessageList(
                    messages = messages,
                    currentUserId = currentUserId,
                    listState = listState,
                    modifier = Modifier.weight(1f)
                )
            }

            InputBar(
                draft = draft,
                onDraftChange = { draft = it },
                emojiOpen = showEmojiPanel,
                onEmojiToggle = { togglePanel("emoji") },
                actionOpen = showActionPanel,
                onActionToggle = { togglePanel("action") },
                audioOpen = showAudioPanel,
                onAudioToggle = { togglePanel("audio") },
                onPickImageDirect = { imagePicker.launch("image/*") },
                isRecording = isRecording,
                recordingDuration = recordingDuration,
                onStopRecord = stopRecordingAndSend,
                onCancelRecord = cancelRecording,
                onSend = sendText,
                onFocused = {
                    // Keyboard opened → close all panels.
                    showEmojiPanel = false
                    showActionPanel = false
                    showAudioPanel = false
                    scope.launch { scrollToBottom() }
                },
                uploading = isUploading,
            )

            when {
                showEmojiPanel -> EmojiPanel(onPick = { emoji -> draft += emoji })
                showActionPanel -> ActionPanel(
                    onPickImage = { showActionPanel = false; imagePicker.launch("image/*") },
                    onPickFile = { showActionPanel = false; filePicker.launch("*/*") },
                    onSendLocation = { showActionPanel = false; sendLocation() },
                    onPickContact = { showActionPanel = false; showContactPicker = true },
                )
                showAudioPanel -> AudioPanel(
                    audioModeText = audioModeText,
                    onAudioModeText = { audioModeText = it },
                    isRecording = isRecording,
                    recordingDuration = recordingDuration,
                    audioAmplitude = audioAmplitude,
                    amplitudeHistory = amplitudeHistory,
                    audioHandsFreeMode = audioHandsFreeMode,
                    onSetHandsFree = { audioHandsFreeMode = it },
                    onStartRecord = startRecording,
                    onStopRecordAndSend = stopRecordingAndSend,
                    onCancelRecord = cancelRecording,
                    onStopRecordKeepFile = {
                        // Hands-free: stop recorder but KEEP the file + mode so
                        // the review screen (Delete / Send / Replay) activates.
                        try { recorder.value?.stop(); recorder.value?.release() } catch (_: Exception) {}
                        recorder.value = null
                        isRecording = false
                    },
                    audioFile = audioFile,
                    recorder = recorder,
                    sttListening = sttListening,
                    sttPartialText = sttPartialText,
                    sttHandsFree = sttHandsFree,
                    onSetSttHandsFree = { sttHandsFree = it },
                    onStartStt = startStt,
                    onStopStt = stopStt,
                    onCancelStt = cancelStt,
                    onStopSttAndSend = stopSttAndSend,
                    previewPlaying = previewPlaying,
                    previewPlayer = previewPlayer,
                    onTogglePreview = {
                        try {
                            if (previewPlaying) { previewPlayer.value?.pause(); previewPlaying = false }
                            else if (previewPlayer.value == null && audioFile.exists() && audioFile.length() > 0) {
                                val mp = android.media.MediaPlayer()
                                mp.setDataSource(audioFile.absolutePath)
                                mp.setOnCompletionListener { previewPlaying = false }
                                mp.prepare(); mp.start()
                                previewPlayer.value = mp; previewPlaying = true
                            } else if (previewPlayer.value != null) {
                                previewPlayer.value?.start(); previewPlaying = true
                            }
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(context, "Không thể phát", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSendRecorded = sendRecordedAudioFile,
                    onDeleteRecorded = {
                        previewPlayer.value?.release(); previewPlayer.value = null
                        previewPlaying = false
                        audioFile.delete()
                        audioHandsFreeMode = false
                        recordingDuration = 0
                        amplitudeHistory.clear()
                    },
                )
            }
        }
    }

    if (showContactPicker) {
        ContactPickerDialog(
            api = container.api,
            onDismiss = { showContactPicker = false },
            onSend = { friendId, includePhone ->
                container.socket.sendMessage(
                    roomId = roomId,
                    type = "contact",
                    content = friendId.toString(),
                    fileUrl = if (includePhone) "include_phone" else null,
                )
                showContactPicker = false
                android.widget.Toast.makeText(context, "Đã gửi danh thiếp", android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun BubbleHeader(
    room: Room?,
    onOpenInApp: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
) {
    val displayName = room?.other_user?.displayName ?: room?.name ?: "Đang tải..."
    val isOnline = room?.other_user?.is_online == true
    val presenceText = if (isOnline) "Đang online" else "Vừa truy cập"
    var menuOpen by remember { mutableStateOf(false) }

    // Compact header tuned to ~46dp (was ~40dp; +15% from user feedback —
    // IconButtons 41dp + outer padding 6dp). Text line-height collapsed to
    // font-size to remove ~6sp of unused leading per line.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF4FC3F7), Color(0xFF29B6F6))
                )
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Chat icon + name share one click target → open the room in
            // the full app. Use wrapContentWidth (not weight) so the clickable
            // area hugs only the icon + name; otherwise the row stretches to
            // touch the Call button and a near-edge tap on Call mis-fires the
            // open-in-app navigation instead.
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenInApp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Custom thin-weight chat icon (res/drawable/ic_bubble_chat.xml).
                // +30% horizontal padding (vs the tight default box) for
                // breathing room around the slim line strokes.
                Icon(
                    painter = painterResource(R.drawable.ic_bubble_chat),
                    contentDescription = "Mở trong ứng dụng",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 4.dp) // ~30% extra width
                        .size(24.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        displayName,
                        color = Color.White,
                        fontSize = 16.sp,    // 14 → 16 (+15%)
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        presenceText,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.sp,     // 10 → 9 (giảm)
                        lineHeight = 9.sp,
                    )
                }
            }
            // Flex spacer pushes the call buttons to the far right and is
            // NOT clickable — taps on this empty area do nothing rather than
            // mis-triggering open-in-app.
            Spacer(Modifier.weight(1f))
            // Audio call — 27dp (-5% from 28 per user feedback).
            IconButton(onClick = onAudioCall, modifier = Modifier.size(41.dp)) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = "Gọi thoại",
                    tint = Color.White,
                    modifier = Modifier.size(27.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            // Video call — kept at 28dp per user feedback.
            IconButton(onClick = onVideoCall, modifier = Modifier.size(41.dp)) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = "Gọi video",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            // 3-dots menu — 25dp (+5% from 24 per user feedback).
            // Per project memory rule, TODO buttons stay visible but disabled.
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(41.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Tuỳ chọn",
                        tint = Color.White,
                        modifier = Modifier.size(25.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DisabledMenuItem("Tìm tin nhắn (sắp ra mắt)")
                    DisabledMenuItem("Tắt thông báo (sắp ra mắt)")
                    DisabledMenuItem("Cài đặt phòng (sắp ra mắt)")
                }
            }
        }
    }
}

@Composable
private fun DisabledMenuItem(label: String) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp) },
        onClick = { /* TODO Phase 2D */ },
        enabled = false,
        modifier = Modifier.alpha(0.55f)
    )
}


@Composable
private fun MessageList(
    messages: List<Message>,
    currentUserId: Int,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Chưa có tin nhắn", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    // Mirror ChatScreen rendering so the bubble and the full chat look
    // identical in one conversation:
    //   - reverseLayout=true (newest at visual bottom, oldest at top)
    //   - pass messages.reversed() so index 0 = newest message
    //   - precompute date separator + time config per message
    //   - reuse vn.chat9.app.ui.chat.MessageBubble — same colors, shapes,
    //     avatars, image/file/contact/location/call/recalled renderers
    val messageDisplayInfo = remember(messages) {
        val info = mutableMapOf<Int, Pair<Boolean, TimeDisplayConfig>>()
        for (i in messages.indices) {
            val msg = messages[i]
            val prev = if (i > 0) messages[i - 1] else null
            val next = if (i < messages.size - 1) messages[i + 1] else null
            val showDateSep = prev == null || DateUtils.isDifferentDay(prev.created_at, msg.created_at)
            val timeConfig = TimeDisplayProcessor.getConfig(
                type = msg.type,
                createdAt = msg.created_at,
                userId = msg.user_id,
                nextUserId = next?.user_id,
                nextCreatedAt = next?.created_at,
            )
            info[msg.id] = Pair(showDateSep, timeConfig)
        }
        info
    }

    // "Silent" initial positioning — list invisible (alpha 0), jump to newest
    // (index 0 because reverseLayout=true), then flip to alpha 1. User never
    // sees the scroll.
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty() && !positioned) {
            listState.scrollToItem(0)
            positioned = true
        }
    }

    // Realtime append — animate to newest (index 0) only if user is already
    // near the bottom. In reverseLayout, firstVisibleItemIndex <= 1 means
    // the user's viewport covers the newest messages.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (positioned && messages.isNotEmpty()) {
            if (listState.firstVisibleItemIndex <= 1) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val reversedMessages = messages.reversed()
    val lastMsgId = messages.lastOrNull()?.id

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .alpha(if (positioned) 1f else 0f),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(reversedMessages, key = { it.id }) { msg ->
            val isMine = msg.user_id == currentUserId
            val status = if (isMine && msg.id == lastMsgId) "received" else null
            val (showDateSep, timeConfig) = messageDisplayInfo[msg.id]
                ?: Pair(false, TimeDisplayConfig(true, TimePosition.INSIDE_BUBBLE, TimeStyle.NORMAL))

            Column {
                if (showDateSep) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(Color(0xFFD0D0D0)))
                        Text(
                            text = DateUtils.formatDateSeparator(msg.created_at),
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        Box(modifier = Modifier.weight(1f).height(0.5.dp).background(Color(0xFFD0D0D0)))
                    }
                }
                // Reuse the full-chat MessageBubble. Phase 2C leaves all the
                // interaction callbacks null (reply, swipe, long-press, quick
                // react, pill click) — those land in Phase 2D. Visual output
                // is already 1:1 with the full chat.
                MessageBubble(
                    message = msg,
                    isSent = isMine,
                    currentUserId = currentUserId,
                    displayName = msg.username ?: "",
                    deliveryStatus = status,
                    timeConfig = timeConfig,
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    emojiOpen: Boolean,
    onEmojiToggle: () -> Unit,
    actionOpen: Boolean,
    onActionToggle: () -> Unit,
    audioOpen: Boolean,
    onAudioToggle: () -> Unit,
    onPickImageDirect: () -> Unit,
    isRecording: Boolean,
    recordingDuration: Int,
    onStopRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onSend: () -> Unit,
    onFocused: () -> Unit,
    uploading: Boolean,
) {
    val canSend = draft.trim().isNotEmpty()

    // Recording state replaces the whole input bar with a compact strip:
    // [Cancel ✕]  ● 00:12  [Stop = send]
    if (isRecording) {
        Surface(color = Color(0xFFFFF4F4), shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancelRecord, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, "Huỷ ghi âm", tint = Color(0xFFD32F2F))
                }
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFD32F2F)))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Đang ghi  %02d:%02d".format(recordingDuration / 60, recordingDuration % 60),
                    color = Color(0xFFD32F2F),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onStopRecord,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFD32F2F))
                ) {
                    Icon(Icons.Filled.Stop, "Gửi", tint = Color.White)
                }
            }
        }
        return
    }

    // Track focus on the text field so the parent can scroll the message list
    // to the bottom the instant the keyboard opens (Zalo-style).
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }

    // Layout mirrors ChatScreen (ChatScreen.kt:1087-1200):
    //   Empty draft : [emoji] [text field] [more ⋯] [mic 🎤] [image 🖼️]
    //   With text   : [emoji] [text field] [send ➤]
    Surface(color = Color.White, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [1] Emoji
            IconButton(onClick = onEmojiToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    painterResource(R.drawable.ic_emoji_add),
                    contentDescription = "Emoji",
                    tint = if (emojiOpen) Color(0xFF3E1F91) else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }

            // [2] Text field
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Nhập tin nhắn...", fontSize = 18.sp) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp, max = 120.dp),
                textStyle = TextStyle(fontSize = 18.sp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                ),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                interactionSource = interactionSource,
            )

            // [3] Send-or-actions
            if (canSend) {
                IconButton(onClick = onSend, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Gửi",
                        tint = Color(0xFF3E1F91)
                    )
                }
            } else {
                // More (⋯) — action panel: Location / Document / Contact / Image
                IconButton(onClick = onActionToggle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_more_actions),
                        contentDescription = "Thêm",
                        tint = if (actionOpen) Color(0xFF3E1F91) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                // Mic (🎤) — audio panel: record + STT
                IconButton(onClick = onAudioToggle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_mic_phone),
                        contentDescription = "Âm thanh",
                        tint = if (audioOpen) Color(0xFF3E1F91) else Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
                // Image (🖼️) — direct shortcut to image picker
                IconButton(
                    onClick = onPickImageDirect,
                    enabled = !uploading,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF3E1F91)
                        )
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_image_search),
                            contentDescription = "Ảnh",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Action panel — mirrors ChatScreen's 4-item grid behind the "⋯" button.
 * Vị trí / Tài liệu / Danh thiếp / Ảnh. Location + Contact are disabled
 * placeholders in Phase 2D (per project rule "TODO buttons keep visible
 * but disabled"); File + Image are wired to pickers.
 */
@Composable
private fun ActionPanel(
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onSendLocation: () -> Unit,
    onPickContact: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionPanelItem(
                icon = Icons.Filled.LocationOn, label = "Vị trí", tint = Color(0xFF4CAF50),
                onClick = onSendLocation
            )
            ActionPanelItem(
                icon = Icons.Filled.Description, label = "Tài liệu", tint = Color(0xFF2196F3),
                onClick = onPickFile
            )
            ActionPanelItem(
                icon = Icons.Filled.Person, label = "Danh thiếp", tint = Color(0xFF9C27B0),
                onClick = onPickContact
            )
            ActionPanelItem(
                icon = Icons.Filled.PhotoLibrary, label = "Ảnh", tint = Color(0xFFFF9800),
                onClick = onPickImage
            )
        }
    }
}

@Composable
private fun ActionPanelItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFF2C3E50))
    }
}

/**
 * Audio panel — full ChatScreen parity (ChatScreen.kt:1486-1834).
 * Layout (top → bottom):
 *   • Waveform bar (visible during record/review)
 *   • Instruction text + secondary hint
 *   • Mic button row: cancel left | giant pulsing mic center | lock hint right
 *   • Mode tabs: "Gửi bản ghi âm" / "Gửi dạng văn bản"
 *
 * Gestures on the mic button:
 *   • Hold-to-talk (record while pressed, send on release)
 *   • Swipe right → hands-free mode (locked recording, then review screen)
 *   • Swipe left → cancel
 */
@Composable
private fun AudioPanel(
    audioModeText: Boolean,
    onAudioModeText: (Boolean) -> Unit,
    isRecording: Boolean,
    recordingDuration: Int,
    audioAmplitude: Float,
    amplitudeHistory: List<Float>,
    audioHandsFreeMode: Boolean,
    onSetHandsFree: (Boolean) -> Unit,
    onStartRecord: () -> Unit,
    onStopRecordAndSend: () -> Unit,
    onCancelRecord: () -> Unit,
    onStopRecordKeepFile: () -> Unit,
    audioFile: java.io.File,
    recorder: androidx.compose.runtime.MutableState<android.media.MediaRecorder?>,
    sttListening: Boolean,
    sttPartialText: String,
    sttHandsFree: Boolean,
    onSetSttHandsFree: (Boolean) -> Unit,
    onStartStt: () -> Unit,
    onStopStt: () -> Unit,
    onCancelStt: () -> Unit,
    onStopSttAndSend: () -> Unit,
    previewPlaying: Boolean,
    previewPlayer: androidx.compose.runtime.MutableState<android.media.MediaPlayer?>,
    onTogglePreview: () -> Unit,
    onSendRecorded: () -> Unit,
    onDeleteRecorded: () -> Unit,
) {
    val pulseScale by animateFloatAsState(
        targetValue = if (isRecording) 1f + audioAmplitude * 0.5f else 1f,
        animationSpec = tween(100), label = "pulse"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (isRecording && audioAmplitude > 0.05f) 0.3f + audioAmplitude * 0.4f else 0f,
        animationSpec = tween(100), label = "ring"
    )
    val btnColor = when {
        audioModeText && sttListening -> Color(0xFFE53935)
        audioModeText -> Color(0xFF4CAF50)
        else -> Color(0xFF2196F3)
    }
    val showWaveform = isRecording || (audioHandsFreeMode && !isRecording && audioFile.exists())
    val isHandsFreeReview = audioHandsFreeMode && !isRecording && audioFile.exists()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color.White)
    ) {
        // === TOP: Waveform bar ===
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFF0F4FF),
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                .graphicsLayer { alpha = if (showWaveform) 1f else 0f }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.weight(1f).height(24.dp)) {
                    val bars = amplitudeHistory.takeLast(40)
                    val barW = if (bars.isNotEmpty()) size.width / (bars.size * 2f) else 4f
                    val centerY = size.height / 2
                    bars.forEachIndexed { i, amp ->
                        val h = (amp * 0.7f + 0.3f) * size.height * 0.8f
                        drawLine(
                            Color(0xFF2196F3),
                            Offset(i * barW * 2 + barW / 2, centerY - h / 2),
                            Offset(i * barW * 2 + barW / 2, centerY + h / 2),
                            strokeWidth = barW * 0.8f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "%02d:%02d".format(recordingDuration / 60, recordingDuration % 60),
                    fontSize = 14.sp,
                    color = Color(0xFF555555)
                )
            }
        }

        // === CENTER COLUMN ===
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val instrText = when {
                isRecording && !audioHandsFreeMode -> "Thả tay để gửi"
                sttListening && !sttHandsFree && sttPartialText.isNotBlank() -> sttPartialText
                sttListening && !sttHandsFree -> "Đang nghe..."
                sttListening && sttHandsFree && sttPartialText.isNotBlank() -> sttPartialText
                sttListening && sttHandsFree -> "Đang nghe..."
                isHandsFreeReview -> ""
                audioModeText -> "Nhấn giữ để nói, chuyển thành văn bản"
                else -> "Bấm giữ để ghi âm"
            }
            val instrText2 = when {
                isRecording && !audioHandsFreeMode -> "Vuốt sang phải để bật chế độ rảnh tay"
                sttListening && !sttHandsFree -> "Vuốt sang phải để bật chế độ rảnh tay"
                else -> ""
            }

            Spacer(Modifier.weight(1f))

            // Instruction text — fixed 54dp box, bottom-aligned, clipped
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 24.dp)
                    .clipToBounds()
                    .graphicsLayer { alpha = if (instrText.isNotEmpty()) 1f else 0f },
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    instrText,
                    fontSize = 14.sp,
                    color = if (sttListening) Color(0xFF2196F3) else Color(0xFF7F8C8D),
                    fontWeight = if (sttListening) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .wrapContentHeight(unbounded = true, align = Alignment.Bottom)
                )
            }
            Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.Center) {
                if (instrText2.isNotEmpty()) {
                    Text(instrText2, fontSize = 12.sp, color = Color(0xFF9E9E9E))
                }
            }

            Spacer(Modifier.height(8.dp))

            // === Buttons ===
            when {
                // STT hands-free
                sttHandsFree && sttListening -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.width(60.dp).clickable(onClick = onCancelStt),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Delete, "Hủy", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFFE53935).copy(alpha = 0.3f)))
                            Box(
                                modifier = Modifier.size(77.dp).clip(CircleShape).background(Color(0xFFE53935))
                                    .clickable(onClick = onStopSttAndSend),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Stop, "Gửi", tint = Color.White, modifier = Modifier.size(38.dp)) }
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(modifier = Modifier.width(60.dp))
                    }
                }
                // Audio hands-free recording
                audioHandsFreeMode && isRecording -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.width(60.dp).clickable {
                                onCancelRecord()
                                onSetHandsFree(false)
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Delete, "Hủy", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(96.dp)
                                    .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = ringAlpha }
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935).copy(alpha = 0.3f))
                            )
                            Box(
                                modifier = Modifier.size(77.dp).clip(CircleShape).background(Color(0xFFE53935))
                                    .clickable(onClick = onStopRecordKeepFile),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(38.dp)) }
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(modifier = Modifier.width(60.dp))
                    }
                }
                // Hands-free review (post-recording)
                isHandsFreeReview -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.width(60.dp).clickable(onClick = onDeleteRecorded),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Delete, "Xóa", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2196F3).copy(alpha = 0.15f)))
                            Box(
                                modifier = Modifier.size(77.dp).clip(CircleShape).background(Color(0xFF2196F3))
                                    .clickable(onClick = onSendRecorded),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.PlayArrow, "Gửi", tint = Color.White, modifier = Modifier.size(36.dp)) }
                        }
                        Spacer(Modifier.width(24.dp))
                        Box(
                            modifier = Modifier.width(60.dp).clickable(onClick = onTogglePreview),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.GraphicEq, "Nghe lại", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                        }
                    }
                }
                // Default — hold-to-talk button
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Left: trash hint when active
                        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                            if (isRecording) Icon(Icons.Filled.Delete, "Xóa", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(24.dp))
                        // Center: mic — hold-to-talk + gestures
                        Box(contentAlignment = Alignment.Center) {
                            if (isRecording || (sttListening && !sttHandsFree)) {
                                Box(
                                    Modifier.size(96.dp)
                                        .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = if (isRecording) ringAlpha else 0.4f }
                                        .clip(CircleShape).background(btnColor.copy(alpha = 0.3f))
                                )
                            }
                            Box(
                                modifier = Modifier.size(77.dp).clip(CircleShape).background(btnColor)
                                    .pointerInput(audioModeText) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val down = awaitPointerEvent()
                                                if (down.changes.any { it.pressed }) {
                                                    val startX = down.changes.first().position.x
                                                    if (audioModeText) onStartStt() else onStartRecord()
                                                    var swipedRight = false
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull() ?: break
                                                        if (!change.pressed) {
                                                            if (swipedRight) {
                                                                if (audioModeText) onSetSttHandsFree(true)
                                                                else onSetHandsFree(true)
                                                            } else {
                                                                if (audioModeText) {
                                                                    onStopSttAndSend()
                                                                } else {
                                                                    onStopRecordAndSend()
                                                                }
                                                            }
                                                            break
                                                        }
                                                        val dragX = change.position.x - startX
                                                        if (dragX > 80) swipedRight = true
                                                        if (dragX < -80) {
                                                            if (audioModeText) onCancelStt()
                                                            else onCancelRecord()
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (sttListening) {
                                    Icon(Icons.Filled.Stop, "Dừng", tint = Color.White, modifier = Modifier.size(38.dp))
                                } else {
                                    Icon(painterResource(R.drawable.ic_mic_phone), "Record", tint = Color.White, modifier = Modifier.size(38.dp))
                                }
                            }
                        }
                        Spacer(Modifier.width(24.dp))
                        // Right: lock hint when active
                        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                            if (isRecording || (sttListening && !sttHandsFree)) {
                                Icon(Icons.Filled.Lock, "Rảnh tay", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // === BOTTOM: mode tabs ===
            val tabsVisible = !isRecording && !audioHandsFreeMode && !sttListening
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                    .graphicsLayer { alpha = if (tabsVisible) 1f else 0f },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Gửi bản ghi âm", fontSize = 14.sp,
                    color = if (!audioModeText) Color(0xFF2C3E50) else Color(0xFF7F8C8D),
                    fontWeight = if (!audioModeText) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .border(if (!audioModeText) 1.5.dp else 1.dp,
                            if (!audioModeText) Color(0xFF2C3E50) else Color(0xFFE0E0E0),
                            RoundedCornerShape(20.dp))
                        .then(if (tabsVisible) Modifier.clickable { onAudioModeText(false) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Gửi dạng văn bản", fontSize = 14.sp,
                    color = if (audioModeText) Color(0xFF2C3E50) else Color(0xFF7F8C8D),
                    fontWeight = if (audioModeText) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .border(if (audioModeText) 1.5.dp else 1.dp,
                            if (audioModeText) Color(0xFF2C3E50) else Color(0xFFE0E0E0),
                            RoundedCornerShape(20.dp))
                        .then(if (tabsVisible) Modifier.clickable { onAudioModeText(true) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Contact picker dialog — Zalo-style friend selector. Opens full-window
 * (over the bubble) with search + single-select + phone-toggle. Compact
 * version of ChatScreen's ContactPicker (no alphabetical group headers,
 * no alpha rail, no swipe-to-dismiss — fits the bubble use case).
 */
@Composable
private fun ContactPickerDialog(
    api: vn.chat9.app.data.api.ApiService,
    onDismiss: () -> Unit,
    onSend: (friendId: Int, includePhone: Boolean) -> Unit,
) {
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var selectedFriendId by remember { mutableStateOf<Int?>(null) }
    var includePhone by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val res = api.getFriends("friends")
            if (res.success && res.data != null) friends = res.data
        } catch (_: Exception) {}
        loading = false
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gửi danh thiếp", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Đã chọn: ${if (selectedFriendId != null) 1 else 0}/1",
                            fontSize = 13.sp, color = Color.Gray
                        )
                    }
                    if (selectedFriendId != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { selectedFriendId?.let { onSend(it, includePhone) } }
                        ) {
                            Text("Gửi", fontSize = 16.sp, color = Color(0xFF3E1F91), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Search
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color(0xFFF5F5F5))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search, null,
                        tint = Color.Gray, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = search, onValueChange = { search = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (search.isEmpty()) Text("Tìm bạn bè", fontSize = 15.sp, color = Color(0xFFAAAAAA))
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))

                // List
                if (loading) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = Color(0xFF3E1F91), modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    val filtered = friends.filter {
                        search.isBlank() || it.username.contains(search, ignoreCase = true)
                    }.sortedBy { it.username.lowercase() }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filtered, key = { it.id }) { friend ->
                            val isSelected = selectedFriendId == friend.id
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { selectedFriendId = if (isSelected) null else friend.id }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val avatarUrl = UrlUtils.toFullUrl(friend.avatar)
                                if (avatarUrl != null) {
                                    AsyncImage(
                                        model = avatarUrl, contentDescription = null,
                                        modifier = Modifier.size(44.dp).clip(CircleShape)
                                    )
                                } else {
                                    Box(
                                        Modifier.size(44.dp).clip(CircleShape)
                                            .background(Color(0xFF3E1F91)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            friend.username.first().uppercase(),
                                            color = Color.White, fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(friend.username, fontSize = 16.sp, color = Color(0xFF2C3E50))
                                }
                                // Radio
                                Box(
                                    modifier = Modifier.size(22.dp).clip(CircleShape)
                                        .border(
                                            2.dp,
                                            if (isSelected) Color(0xFF3E1F91) else Color(0xFFD0D0D0),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            Modifier.size(14.dp).clip(CircleShape)
                                                .background(Color(0xFF3E1F91))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom: phone toggle
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFFF9F9F9))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Gửi kèm số điện thoại", fontSize = 14.sp, color = Color(0xFF555555))
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.material3.Switch(
                        checked = includePhone, onCheckedChange = { includePhone = it },
                        modifier = Modifier.graphicsLayer {
                            scaleX = 0.85f
                            scaleY = 0.65f // -35% height per user feedback
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF3E1F91)
                        )
                    )
                }
            }
        }
    }
}

/**
 * Emoji picker panel — mirrors ChatScreen's emoji panel:
 * 6 categories × grid of 8 columns. Tap an emoji to append to the draft.
 * Fixed 280dp height so it slots in where the keyboard used to be.
 */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val categories = remember {
        linkedMapOf(
            "Mặt cười" to listOf("😀","😁","😂","🤣","😃","😄","😅","😆","😉","😊","😋","😎","😍","🥰","😘","😗","😙","😚","🙂","🤗","🤩","🤔","🤨","😐","😑","😶","🙄","😏","😣","😥","😮","🤐","😯","😪","😫","😴","😌","😛","😜","😝","🤤","😒","😓","😔","😕","🙃","🤑","😲","🙁","😖","😞","😟","😤","😢","😭","😦","😧","😨","😩","🤯","😬","😰","😱","🥵","🥶","😳","🤪","😵","🥴","😠","😡","🤬","😈","👿","💀","☠️","💩","🤡","👹","👺","👻","👽","👾","🤖"),
            "Cử chỉ" to listOf("👋","🤚","🖐️","✋","🖖","👌","🤌","🤏","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🤝","🙏","✍️","💪","🦾","🦿","🦵","🦶"),
            "Trái tim" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟"),
            "Đồ vật" to listOf("🎉","🎊","🎈","🎁","🎀","🏆","🥇","🥈","🥉","⚽","🏀","🏈","⚾","🎾","🏐","🎮","🎲","🎯","🎵","🎶","🎤","🎧","📱","💻","⌨️","📷","📹","🔔","📌","📎","✏️","📝","📁","🗑️"),
            "Ăn uống" to listOf("🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥝","🍅","🥑","🍔","🍟","🍕","🌭","🌮","🍣","🍜","🍝","🍰","🎂","🍩","🍪","☕","🍵","🧃","🍺","🍷","🥤"),
            "Thiên nhiên" to listOf("🌞","🌝","🌛","⭐","🌟","🌈","☁️","⛅","🌧️","⛈️","❄️","💧","🔥","🌊","🌸","🌺","🌻","🌹","🍀","🌿","🍃","🍂","🍁","🌴","🌵","🐶","🐱","🐭","🐰","🦊","🐻","🐼","🐨","🐯","🦁")
        )
    }
    var activeCategory by remember { mutableStateOf(categories.keys.first()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color.White)
    ) {
        // Category tabs
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            categories.keys.forEach { cat ->
                val isActive = cat == activeCategory
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isActive) Color(0xFFF0EEFF) else Color.Transparent)
                        .clickable { activeCategory = cat }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(categories[cat]!!.first(), fontSize = 20.sp)
                }
            }
        }
        // Emoji grid
        val emojis = categories[activeCategory] ?: emptyList()
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(emojis.size) { idx ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(emojis[idx]) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(emojis[idx], fontSize = 25.sp)
                }
            }
        }
    }
}
