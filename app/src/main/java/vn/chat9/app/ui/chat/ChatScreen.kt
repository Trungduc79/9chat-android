package vn.chat9.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import vn.chat9.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import vn.chat9.app.App
import vn.chat9.app.data.model.Message
import vn.chat9.app.data.model.Room
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.TimeDisplayConfig
import vn.chat9.app.util.TimeDisplayProcessor
import vn.chat9.app.util.TimePosition
import vn.chat9.app.util.TimeStyle
import vn.chat9.app.data.model.Friend
import vn.chat9.app.data.model.User
import vn.chat9.app.util.UrlUtils

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(room: Room, scrollToMessageId: Int? = null, startWithSearch: Boolean = false, onBack: () -> Unit, onVoiceCall: () -> Unit = {}, onVideoCall: () -> Unit = {}, onUserWall: (Int) -> Unit = {}, onChatOptions: () -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val container = (context.applicationContext as App).container
    val currentUserId = container.tokenManager.user?.id ?: 0
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    // When non-null, a full-screen image gallery opens with every image
    // message in the chat, centred on this message id. Tap any image bubble
    // to set it; GalleryViewerDialog builds the swipeable list from
    // `messages.filter { it.type == "image" }`.
    var galleryForMessageId by remember { mutableStateOf<Int?>(null) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMoreMessages by remember { mutableStateOf(true) }
    var isUploading by remember { mutableStateOf(false) }
    var showActionPanel by remember { mutableStateOf(false) }
    var showAudioPanel by remember { mutableStateOf(false) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    var showContactPicker by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val hideKeyboardAndPanels = remember {
        {
            keyboardController?.hide()
            focusManager.clearFocus()
            showActionPanel = false
            showAudioPanel = false
            showEmojiPanel = false
        }
    }
    // Track keyboard height via ViewTreeObserver (works without edge-to-edge)
    var isKeyboardVisible by remember { mutableStateOf(false) }
    var lastKeyboardHeight by remember { mutableStateOf(260.dp) }
    val view = LocalView.current
    DisposableEffect(view) {
        // Measure baseline (no keyboard) to get nav bar offset
        var baselineBottom = 0
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(rect)
            val screenHeight = view.rootView.height
            val kbHeight = screenHeight - rect.bottom

            // Record baseline (no keyboard state) — this is nav bar + system UI
            if (kbHeight < screenHeight * 0.15) {
                baselineBottom = screenHeight - rect.bottom
            }

            val kbVisible = kbHeight > screenHeight * 0.15
            isKeyboardVisible = kbVisible
            if (kbVisible) {
                // Subtract baseline (nav bar) to get keyboard-only height
                val kbOnly = (kbHeight - baselineBottom).coerceAtLeast(0)
                lastKeyboardHeight = with(density) { kbOnly.toDp() }
            }
            android.util.Log.d("PANEL_DBG", "screenH=$screenHeight rectBot=${rect.bottom} kbH=$kbHeight baseline=$baselineBottom kbOnly=${kbHeight - baselineBottom} lastH=$lastKeyboardHeight vis=$kbVisible")
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var replyToMessage by remember { mutableStateOf<Message?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var forwardMessage by remember { mutableStateOf<Message?>(null) }
    var highlightedMessageId by remember { mutableStateOf(scrollToMessageId) }
    var pinnedMessages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var lastSeenMessageId by remember { mutableIntStateOf(0) } // last message seen by other user
    var isTyping by remember { mutableStateOf(false) } // other user typing
    var typingUsername by remember { mutableStateOf("") }
    var iAmTyping by remember { mutableStateOf(false) }
    var showInChatSearch by remember { mutableStateOf(startWithSearch) }
    var inChatSearchQuery by remember { mutableStateOf("") }
    var inChatSearchResults by remember { mutableStateOf<List<Int>>(emptyList()) } // message indices
    var currentSearchIndex by remember { mutableIntStateOf(0) }
    var showReactionDetail by remember { mutableStateOf(false) }
    var reactionDetailMessageId by remember { mutableIntStateOf(0) }
    var reactionDetailData by remember { mutableStateOf<vn.chat9.app.data.model.ReactionDetailResponse?>(null) }
    var reactionDetailLoading by remember { mutableStateOf(false) }
    val reactionEffects = remember { mutableStateListOf<FloatingEmoji>() }
    val particleSystem = remember { ParticleSystem(100) }
    var effectIdCounter by remember { mutableLongStateOf(0L) }
    var reactionToast by remember { mutableStateOf<ReactionToast?>(null) }
    var overlayRootOffset by remember { mutableStateOf(Offset.Zero) }
    var friendIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var sentRequestIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Load friend IDs + sent requests once
    LaunchedEffect(Unit) {
        try {
            val res = container.api.getFriends("friends")
            if (res.success && res.data != null) friendIds = res.data.map { it.id }.toSet()
        } catch (_: Exception) {}
        try {
            val res = container.api.getFriends("sent")
            if (res.success && res.data != null) sentRequestIds = res.data.map { it.id }.toSet()
        } catch (_: Exception) {}
    }

    // Reusable scroll-to-center + highlight (reverseLayout: convert index)
    fun scrollToAndHighlight(messageId: Int) {
        val origIndex = messages.indexOfFirst { it.id == messageId }
        val index = if (origIndex >= 0) messages.size - 1 - origIndex else -1
        if (index >= 0) {
            scope.launch {
                listState.scrollToItem(index)
                kotlinx.coroutines.delay(100)
                val li = listState.layoutInfo
                val ii = li.visibleItemsInfo.firstOrNull { it.index == index }
                if (ii != null) {
                    val center = (li.viewportEndOffset - li.viewportStartOffset) / 2
                    listState.animateScrollToItem(index, -(center - ii.size / 2))
                }
                highlightedMessageId = messageId
                kotlinx.coroutines.delay(5000)
                highlightedMessageId = null
            }
        } else {
            // Message not loaded — load older messages until we find it
            scope.launch {
                var found = false
                var attempts = 0
                while (!found && attempts < 10) {
                    attempts++
                    val oldestId = messages.firstOrNull()?.id ?: break
                    if (messageId >= oldestId) break // message should be in range but wasn't found
                    try {
                        val res = container.api.getMessages(room.id, beforeId = oldestId)
                        if (res.success && res.data != null && res.data.messages.isNotEmpty()) {
                            messages = res.data.messages + messages
                            hasMoreMessages = res.data.has_more
                            val newIndex = messages.indexOfFirst { it.id == messageId }
                            if (newIndex >= 0) {
                                found = true
                                kotlinx.coroutines.delay(100)
                                listState.scrollToItem(newIndex)
                                kotlinx.coroutines.delay(100)
                                val li = listState.layoutInfo
                                val ii = li.visibleItemsInfo.firstOrNull { it.index == newIndex }
                                if (ii != null) {
                                    val center = (li.viewportEndOffset - li.viewportStartOffset) / 2
                                    listState.animateScrollToItem(newIndex, -(center - ii.size / 2))
                                }
                                highlightedMessageId = messageId
                                kotlinx.coroutines.delay(5000)
                                highlightedMessageId = null
                            }
                        } else break
                    } catch (_: Exception) { break }
                }
            }
        }
    }
    var showAllPinned by remember { mutableStateOf(false) }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isUploading = true
            try {
                // Check file size before reading into memory (max 50MB)
                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                if (fileSize > 50 * 1024 * 1024) {
                    android.widget.Toast.makeText(context, "File quá lớn (tối đa 50MB)", android.widget.Toast.LENGTH_SHORT).show()
                    isUploading = false; return@launch
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: return@launch
                inputStream.close()

                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mimeType.contains("png") -> "png"
                    mimeType.contains("gif") -> "gif"
                    mimeType.contains("webp") -> "webp"
                    else -> "jpg"
                }
                val fileName = "image_${System.currentTimeMillis()}.$ext"

                val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)
                val roomIdBody = room.id.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                val res = withContext(Dispatchers.IO) {
                    container.api.uploadFile(filePart, roomIdBody)
                }

                if (res.success && res.data != null) {
                    container.socket.sendMessage(
                        roomId = room.id,
                        type = "image",
                        content = "",
                        fileUrl = res.data.file_url,
                        fileName = res.data.file_name,
                        fileSize = res.data.file_size
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("Chat", "Upload failed", e)
                android.widget.Toast.makeText(context, "Lỗi tải ảnh lên", android.widget.Toast.LENGTH_SHORT).show()
            }
            isUploading = false
        }
    }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isUploading = true
            try {
                // Check file size before reading into memory (max 50MB)
                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                if (fileSize > 50 * 1024 * 1024) {
                    android.widget.Toast.makeText(context, "File quá lớn (tối đa 50MB)", android.widget.Toast.LENGTH_SHORT).show()
                    isUploading = false; return@launch
                }

                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: return@launch
                inputStream.close()

                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                var fileName = "file_${System.currentTimeMillis()}"
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) fileName = it.getString(nameIndex)
                    }
                }

                val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)
                val roomIdBody = room.id.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                val res = withContext(Dispatchers.IO) {
                    container.api.uploadFile(filePart, roomIdBody)
                }

                if (res.success && res.data != null) {
                    container.socket.sendMessage(
                        roomId = room.id,
                        type = if (mimeType.startsWith("image")) "image" else "file",
                        content = res.data.file_name,
                        fileUrl = res.data.file_url,
                        fileName = res.data.file_name,
                        fileSize = res.data.file_size
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("Chat", "File upload failed", e)
                android.widget.Toast.makeText(context, "Lỗi tải file lên", android.widget.Toast.LENGTH_SHORT).show()
            }
            isUploading = false
        }
    }

    // Location: fetch fresh GPS + reverse geocode + send
    var locationPending by remember { mutableStateOf(false) }

    fun doFetchAndSendLocation() {
        if (locationPending) return
        locationPending = true
        android.widget.Toast.makeText(context, "Đang lấy vị trí...", android.widget.Toast.LENGTH_SHORT).show()

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val hasGps = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val hasNetwork = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!hasGps && !hasNetwork) {
            android.widget.Toast.makeText(context, "Vui lòng bật định vị", android.widget.Toast.LENGTH_SHORT).show()
            locationPending = false
            return
        }

        @Suppress("MissingPermission")
        val provider = if (hasGps) android.location.LocationManager.GPS_PROVIDER else android.location.LocationManager.NETWORK_PROVIDER
        lm.requestSingleUpdate(provider, object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                val lat = loc.latitude; val lng = loc.longitude
                scope.launch {
                    val address = try {
                        val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=18&addressdetails=1"
                        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "9chat-android/1.0").build()
                        val res = withContext(Dispatchers.IO) { okhttp3.OkHttpClient().newCall(req).execute() }
                        val json = org.json.JSONObject(res.body?.string() ?: "{}")
                        json.optString("display_name", "").ifEmpty { "${"%.6f".format(lat)}, ${"%.6f".format(lng)}" }
                    } catch (_: Exception) { "${"%.6f".format(lat)}, ${"%.6f".format(lng)}" }
                    container.socket.sendMessage(roomId = room.id, type = "location",
                        content = org.json.JSONObject().put("lat", lat).put("lng", lng).put("address", address).toString())
                    locationPending = false
                }
            }
            @Deprecated("Deprecated") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {
                android.widget.Toast.makeText(context, "Định vị đã bị tắt", android.widget.Toast.LENGTH_SHORT).show()
                locationPending = false
            }
        }, android.os.Looper.getMainLooper())

        // Timeout after 15s
        scope.launch {
            kotlinx.coroutines.delay(15000)
            if (locationPending) {
                locationPending = false
                android.widget.Toast.makeText(context, "Hết thời gian chờ vị trí", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            doFetchAndSendLocation()
        } else {
            android.widget.Toast.makeText(context, "Cần quyền truy cập vị trí", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun sendLocation() {
        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            doFetchAndSendLocation()
        } else {
            locationPermLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val otherUser = room.other_user
    val roomName = if (room.type == "private" && otherUser != null) {
        otherUser.displayName
    } else room.name ?: "Chat"

    // Map user_id -> display name: "Bạn" for self, alias for friend, username fallback
    val getDisplayName: (Int?, String?) -> String = { userId, username ->
        when {
            userId == currentUserId -> "Bạn"
            room.type == "private" && otherUser != null && userId == otherUser.id -> otherUser.displayName
            else -> username ?: ""
        }
    }

    val avatarUrl = if (room.type == "private" && otherUser != null) {
        UrlUtils.toFullUrl(otherUser.avatar)
    } else null

    val isOnline = room.type == "private" && otherUser?.is_online == true

    // Load messages + pinned
    LaunchedEffect(room.id) {
        try {
            val res = container.api.getMessages(room.id)
            if (res.success && res.data != null) {
                messages = res.data.messages
                hasMoreMessages = res.data.has_more
            }
        } catch (e: Exception) {
            android.util.Log.e("Chat", "Error loading messages", e)
        }
        try {
            val pinRes = container.api.getPinnedMessages(room.id)
            if (pinRes.success && pinRes.data != null) {
                pinnedMessages = pinRes.data
            }
        } catch (_: Exception) {}
        isLoading = false

        // Scroll to target message or bottom
        if (highlightedMessageId != null && messages.isNotEmpty()) {
            scrollToAndHighlight(highlightedMessageId!!)
        } else if (messages.isNotEmpty()) {
            listState.scrollToItem(0) // reverseLayout: 0 = bottom
        }

    }

    // Auto scroll to bottom when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex < 3) {
            listState.animateScrollToItem(0) // reverseLayout: 0 = bottom
        }
    }

    // Load more messages when scrolling to top (reverseLayout: top = last items)
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMoreMessages && !isLoadingMore && lastVisible >= messages.size - 3 && messages.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            isLoadingMore = true
            try {
                val oldestId = messages.firstOrNull()?.id
                val res = container.api.getMessages(room.id, beforeId = oldestId)
                if (res.success && res.data != null) {
                    val oldMessages = res.data.messages
                    hasMoreMessages = res.data.has_more
                    if (oldMessages.isNotEmpty()) {
                        messages = oldMessages + messages
                        // No scroll adjustment needed — reverseLayout handles it
                    }
                }
            } catch (_: Exception) {}
            isLoadingMore = false
        }
    }

    // Auto hide panel when keyboard shows + auto scroll
    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible) {
            showActionPanel = false
            showAudioPanel = false
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }
    }

    LaunchedEffect(room.id) {
        container.socket.switchRoom(room.id)
    }

    // Mark last message as read after messages are loaded
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            container.socket.markSeen(room.id, messages.last().id)
        }
    }

    // Listen for new messages — clean up on dispose to prevent duplicate listeners
    DisposableEffect(room.id) {
        val messageListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                val msgRoomId = json.getInt("room_id")
                if (msgRoomId == room.id) {
                    val replyMsg = if (json.has("reply_message") && !json.isNull("reply_message")) {
                        val r = json.getJSONObject("reply_message")
                        Message(
                            id = r.optInt("id"), room_id = msgRoomId, user_id = r.optInt("user_id"),
                            type = r.optString("type", "text"), content = if (r.isNull("content")) null else r.optString("content"),
                            file_url = r.optString("file_url", null), file_name = r.optString("file_name", null),
                            file_size = r.optLong("file_size", 0), created_at = r.optString("created_at", ""),
                            username = r.optString("username", null), avatar = r.optString("avatar", null)
                        )
                    } else null

                    // Parse contact_user if present
                    val contactUser = if (json.has("contact_user") && !json.isNull("contact_user")) {
                        val cu = json.getJSONObject("contact_user")
                        User(id = cu.optInt("id"), username = cu.optString("username", ""),
                            avatar = cu.optString("avatar", null), bio = cu.optString("bio", null),
                            phone = cu.optString("phone", null))
                    } else null

                    val msg = Message(
                        id = json.getInt("id"), room_id = msgRoomId, user_id = json.getInt("user_id"),
                        type = json.optString("type", "text"), content = if (json.isNull("content")) null else json.optString("content"),
                        file_url = json.optString("file_url", null), file_name = json.optString("file_name", null),
                        file_size = json.optLong("file_size", 0),
                        reply_to = if (json.has("reply_to") && !json.isNull("reply_to")) json.getInt("reply_to") else null,
                        created_at = json.optString("created_at", "").ifEmpty {
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
                                timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
                            }.format(java.util.Date())
                        },
                        username = json.optString("username", null), avatar = json.optString("avatar", null),
                        reply_message = replyMsg, contact_user = contactUser
                    )
                    messages = messages + msg
                    scope.launch { listState.animateScrollToItem(0) }
                    container.socket.markSeen(room.id, msg.id)
                }
            } catch (e: Exception) {
                android.util.Log.e("Chat", "Error parsing message", e)
            }
        }

        val pinListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                if (json.getInt("room_id") == room.id) {
                    val m = json.getJSONObject("message")
                    val pinned = Message(
                        id = m.getInt("id"), room_id = room.id, user_id = m.optInt("user_id"),
                        type = m.optString("type", "text"), content = if (m.isNull("content")) null else m.optString("content"),
                        file_url = m.optString("file_url", null), file_name = m.optString("file_name", null),
                        file_size = m.optLong("file_size", 0), created_at = m.optString("created_at", ""),
                        username = m.optString("username", null), avatar = m.optString("avatar", null)
                    )
                    pinnedMessages = listOf(pinned) + pinnedMessages
                }
            } catch (_: Exception) {}
        }

        val unpinListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                if (json.getInt("room_id") == room.id) {
                    pinnedMessages = pinnedMessages.filter { it.id != json.getInt("message_id") }
                }
            } catch (_: Exception) {}
        }

        val seenListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                if (json.getInt("room_id") == room.id && json.getInt("user_id") != currentUserId) {
                    val seenId = json.getInt("message_id")
                    if (seenId > lastSeenMessageId) lastSeenMessageId = seenId
                }
            } catch (_: Exception) {}
        }

        val typingListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                if (json.getInt("room_id") == room.id && json.getInt("user_id") != currentUserId) {
                    typingUsername = json.optString("username", "")
                    isTyping = true
                }
            } catch (_: Exception) {}
        }

        val stopTypingListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                if (json.getInt("room_id") == room.id && json.getInt("user_id") != currentUserId) {
                    isTyping = false
                }
            } catch (_: Exception) {}
        }

        val reactionListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                val msgId = json.getInt("message_id")
                val userId = json.optInt("user_id", 0)
                val action = json.optString("action", "")
                val reactionType = json.optString("reaction_type", "")
                val summaryJson = json.getJSONObject("summary")
                val summary = mutableMapOf<String, Int>()
                summaryJson.keys().forEach { key -> summary[key] = summaryJson.getInt(key) }

                // Update summary from server broadcast (authoritative)
                messages = messages.map { msg ->
                    if (msg.id == msgId) {
                        val existing = msg.reactions ?: vn.chat9.app.data.model.ReactionData()
                        msg.copy(reactions = existing.copy(summary = summary))
                    } else msg
                }

                // Trigger animation for other user's reactions
                if (action == "added" && reactionType.isNotEmpty() && userId != currentUserId) {
                    val emoji = reactionTypeToEmoji(reactionType)
                    val screenCenterX = listState.layoutInfo.viewportSize.width / 2f
                    val idx = messages.indexOfFirst { it.id == msgId }
                    val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                    if (itemInfo != null) {
                        val bubbleBottomY = (itemInfo.offset + itemInfo.size).toFloat()
                        val toastCenterY = bubbleBottomY - 80f * density.density + 34f * density.density
                        // Toast
                        val prevToast = reactionToast
                        val newCount = if (prevToast != null && prevToast.messageId == msgId && System.currentTimeMillis() - prevToast.timestamp < 1000) prevToast.count + 1 else 1
                        reactionToast = ReactionToast(msgId, emoji, newCount, screenCenterX, bubbleBottomY, screenCenterX, bubbleBottomY)
                        // Floating emoji
                        if (reactionEffects.size < 15) {
                            reactionEffects.add(FloatingEmoji.create(effectIdCounter++, emoji, screenCenterX, toastCenterY))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val errorListener: (Array<Any>) -> Unit = { args ->
            try {
                val json = args[0] as JSONObject
                val msg = json.optString("message", "Lỗi không xác định")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {}
        }

        container.socket.on("message", messageListener)
        container.socket.on("message_pinned", pinListener)
        container.socket.on("message_unpinned", unpinListener)
        container.socket.on("message_seen", seenListener)
        container.socket.on("typing", typingListener)
        container.socket.on("stop_typing", stopTypingListener)
        container.socket.on("MESSAGE_REACTION_UPDATE", reactionListener)
        container.socket.on("error", errorListener)

        onDispose {
            container.socket.off("message", messageListener)
            container.socket.off("message_pinned", pinListener)
            container.socket.off("message_unpinned", unpinListener)
            container.socket.off("message_seen", seenListener)
            container.socket.off("typing", typingListener)
            container.socket.off("stop_typing", stopTypingListener)
            container.socket.off("MESSAGE_REACTION_UPDATE", reactionListener)
            container.socket.off("error", errorListener)
        }
    }

    Scaffold(
        // navigationBarsPadding keeps the bottom input bar above the system
        // navigation bar in edge-to-edge mode (MainActivity runs
        // decorFitsSystemWindows = false). imePadding handles the soft keyboard.
        // Both combine cleanly: when keyboard opens, navigation-bars inset
        // collapses to 0 so only imePadding applies; when keyboard closes,
        // navigationBarsPadding takes over. Action/audio panels replace the
        // keyboard, so imePadding is skipped for those.
        modifier = (if (showActionPanel || showAudioPanel) Modifier else Modifier.imePadding())
            .navigationBarsPadding(),
        topBar = {
            if (showInChatSearch) {
                // In-chat search bar
                val searchFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(showInChatSearch) { searchFocusRequester.requestFocus() }

                Surface(color = Color.White, shadowElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            showInChatSearch = false
                            inChatSearchQuery = ""
                            inChatSearchResults = emptyList()
                            highlightedMessageId = null
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = inChatSearchQuery,
                            onValueChange = { q ->
                                inChatSearchQuery = q
                                if (q.length >= 2) {
                                    val lq = q.lowercase()
                                    inChatSearchResults = messages.mapIndexedNotNull { idx, msg ->
                                        if (msg.type == "text" && msg.content?.lowercase()?.contains(lq) == true) idx else null
                                    }
                                    currentSearchIndex = if (inChatSearchResults.isNotEmpty()) inChatSearchResults.size - 1 else 0
                                    if (inChatSearchResults.isNotEmpty()) {
                                        val msgId = messages[inChatSearchResults[currentSearchIndex]].id
                                        scrollToAndHighlight(msgId)
                                    }
                                } else {
                                    inChatSearchResults = emptyList()
                                    highlightedMessageId = null
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Color(0xFF2C3E50)),
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFF3E1F91)),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocusRequester),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (inChatSearchQuery.isEmpty()) Text("Tìm tin nhắn văn bản", color = Color.Gray, fontSize = 16.sp)
                                    innerTextField()
                                }
                            }
                        )
                        if (inChatSearchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                inChatSearchQuery = ""
                                inChatSearchResults = emptyList()
                                highlightedMessageId = null
                            }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, "Clear", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            } else {
                // Normal header
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                if (room.type == "private" && otherUser != null) {
                                    onUserWall(otherUser.id)
                                }
                            }
                        ) {
                            Box {
                                if (avatarUrl != null) {
                                    AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = roomName,
                                        modifier = Modifier.size(36.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(roomName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (isOnline) {
                                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White).align(Alignment.BottomEnd)) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00C853)).align(Alignment.Center))
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val headerIsStranger = room.type == "private" && otherUser != null && otherUser.id != currentUserId && !friendIds.contains(otherUser.id)
                                    if (headerIsStranger) {
                                        Text(
                                            "Người lạ",
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                                            modifier = Modifier
                                                .background(Color(0xFFFF9800), RoundedCornerShape(3.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text(roomName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    if (isOnline) "Online" else "Offline",
                                    fontSize = 12.sp,
                                    color = if (isOnline) Color(0xFF00C853) else Color.Gray
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onVoiceCall) {
                            Icon(Icons.Default.Phone, "Call", tint = Color(0xFF3E1F91))
                        }
                        IconButton(onClick = onVideoCall) {
                            Icon(Icons.Default.Videocam, "Video", tint = Color(0xFF3E1F91))
                        }
                        IconButton(onClick = { showInChatSearch = true }) {
                            Icon(Icons.Default.Search, "Search", tint = Color(0xFF3E1F91))
                        }
                        IconButton(onClick = onChatOptions) {
                            Icon(painterResource(R.drawable.ic_more_vert), "More", tint = Color(0xFF3E1F91), modifier = Modifier.size(22.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )
            }
        },
        bottomBar = {
            // Multi-select action bar (web style)
            if (multiSelectMode) {
                val allMine = selectedMessageIds.isNotEmpty() && selectedMessageIds.all { id -> messages.find { it.id == id }?.user_id == currentUserId }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF8E44AD).copy(alpha = 0.68f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Count
                    Text(
                        "${selectedMessageIds.size} đã chọn",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    // Delete
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable { showDeleteConfirm = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Xóa", color = Color.White, fontSize = 12.sp)
                    }
                    // Forward
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable {
                                val firstMsg = messages.firstOrNull { it.id in selectedMessageIds }
                                if (firstMsg != null) { forwardMessage = firstMsg; showForwardDialog = true }
                                multiSelectMode = false; selectedMessageIds = emptySet()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Shortcut, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Chuyển tiếp", color = Color.White, fontSize = 12.sp)
                    }
                    // Recall (only if all mine)
                    if (allMine) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .clickable {
                                    selectedMessageIds.forEach { id ->
                                        container.socket.emit("recall_message", JSONObject().put("message_id", id))
                                    }
                                    messages = messages.map { if (it.id in selectedMessageIds) it.copy(type = "recalled", content = null) else it }
                                    multiSelectMode = false; selectedMessageIds = emptySet()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(painterResource(R.drawable.ic_recall), null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Thu hồi", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    // Close
                    Icon(
                        Icons.Default.Close, "Close", tint = Color.White,
                        modifier = Modifier.size(20.dp).clickable { multiSelectMode = false; selectedMessageIds = emptySet() }
                    )
                }
                return@Scaffold
            }
            if (showInChatSearch) {
                // Search navigation bar
                if (inChatSearchResults.isNotEmpty()) {
                    Surface(color = Color.White, shadowElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Kết quả thứ ${currentSearchIndex + 1}/${inChatSearchResults.size}",
                                fontSize = 13.sp,
                                color = Color(0xFF7F8C8D),
                                modifier = Modifier.weight(1f)
                            )
                            // Previous result (up)
                            IconButton(
                                onClick = {
                                    if (currentSearchIndex > 0) {
                                        currentSearchIndex--
                                        val msgId = messages[inChatSearchResults[currentSearchIndex]].id
                                        scrollToAndHighlight(msgId)
                                    }
                                },
                                enabled = currentSearchIndex > 0,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Previous", tint = if (currentSearchIndex > 0) Color(0xFF3E1F91) else Color(0xFFD0D0D0))
                            }
                            // Next result (down)
                            IconButton(
                                onClick = {
                                    if (currentSearchIndex < inChatSearchResults.size - 1) {
                                        currentSearchIndex++
                                        val msgId = messages[inChatSearchResults[currentSearchIndex]].id
                                        scrollToAndHighlight(msgId)
                                    }
                                },
                                enabled = currentSearchIndex < inChatSearchResults.size - 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "Next", tint = if (currentSearchIndex < inChatSearchResults.size - 1) Color(0xFF3E1F91) else Color(0xFFD0D0D0))
                            }
                        }
                    }
                }
                return@Scaffold
            }
            // Input bar with optional reply preview
            Surface(
                color = Color.White,
                shadowElevation = 4.dp
            ) {
                Column {
                    // Reply preview bar
                    replyToMessage?.let { reply ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF0F2F5))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(36.dp)
                                    .background(Color(0xFF3E1F91), RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    getDisplayName(reply.user_id, reply.username).ifEmpty { "Bạn" },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF3E1F91),
                                    maxLines = 1
                                )
                                Text(
                                    when (reply.type) {
                                        "image" -> "[Hình ảnh]"
                                        "file" -> reply.file_name ?: "[File]"
                                        else -> reply.content ?: ""
                                    },
                                    fontSize = 12.sp,
                                    color = Color(0xFF7F8C8D),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Reply image thumbnail
                            if (reply.type == "image" && reply.file_url != null) {
                                Spacer(Modifier.width(8.dp))
                                AsyncImage(
                                    model = UrlUtils.toFullUrl(reply.file_url),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            IconButton(
                                onClick = { replyToMessage = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, "Cancel reply", tint = Color(0xFF7F8C8D), modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Emoji button
                        IconButton(onClick = {
                            if (!showEmojiPanel) {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                showActionPanel = false; showAudioPanel = false
                                showEmojiPanel = true
                            } else {
                                showEmojiPanel = false
                            }
                        }, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(R.drawable.ic_emoji_add), "Emoji", tint = if (showEmojiPanel) Color(0xFF3E1F91) else Color.Gray, modifier = Modifier.size(24.dp))
                        }

                        // Text input
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                if (it.isNotBlank() && !iAmTyping) {
                                    iAmTyping = true
                                    container.socket.sendTyping(room.id)
                                } else if (it.isBlank() && iAmTyping) {
                                    iAmTyping = false
                                    container.socket.stopTyping(room.id)
                                }
                            },
                            placeholder = { Text("Nhập tin nhắn...", fontSize = 18.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 120.dp)
                                .onFocusChanged { if (it.isFocused) { showActionPanel = false; showAudioPanel = false; showEmojiPanel = false } },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent
                            ),
                            maxLines = 4
                        )

                        // Send or action buttons
                        if (inputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank()) {
                                        container.socket.sendMessage(
                                            roomId = room.id,
                                            type = "text",
                                            content = inputText.trim(),
                                            replyTo = replyToMessage?.id
                                        )
                                        inputText = ""
                                        replyToMessage = null
                                        iAmTyping = false
                                        container.socket.stopTyping(room.id)
                                    }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF3E1F91))
                            }
                    } else {
                        IconButton(
                            onClick = {
                                if (!showActionPanel) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    showAudioPanel = false; showEmojiPanel = false
                                    showActionPanel = true
                                } else {
                                    showActionPanel = false
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(painterResource(R.drawable.ic_more_actions), "More", tint = if (showActionPanel) Color(0xFF3E1F91) else Color.Gray, modifier = Modifier.size(24.dp))
                        }
                        IconButton(
                            onClick = {
                                if (!showAudioPanel) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    showActionPanel = false; showEmojiPanel = false
                                    showAudioPanel = true
                                } else {
                                    showAudioPanel = false
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(painterResource(R.drawable.ic_mic_phone), "Mic", tint = if (showAudioPanel) Color(0xFF3E1F91) else Color.Gray, modifier = Modifier.size(24.dp))
                        }
                        IconButton(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.size(40.dp),
                            enabled = !isUploading
                        ) {
                            if (isUploading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(painterResource(R.drawable.ic_image_search), "Image", tint = Color.Gray, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                // Action panel (toggle with ··· button) — same height as keyboard
                if (showActionPanel) {
                    val panelHeight = lastKeyboardHeight
                    android.util.Log.d("PANEL_DBG", "RENDERING panel height=$panelHeight")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelHeight)
                            .background(Color.White)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ActionPanelItem(Icons.Default.LocationOn, "Vị trí", Color(0xFF4CAF50)) { showActionPanel = false; sendLocation() }
                            ActionPanelItem(Icons.Default.Description, "Tài liệu", Color(0xFF2196F3)) { filePicker.launch("*/*"); showActionPanel = false }
                            ActionPanelItem(Icons.Default.Person, "Danh thiếp", Color(0xFF9C27B0)) { showActionPanel = false; showContactPicker = true }
                            ActionPanelItem(Icons.Default.PhotoLibrary, "Ảnh", Color(0xFFFF9800)) { imagePicker.launch("image/*"); showActionPanel = false }
                        }
                    }
                }
                // Emoji picker panel
                if (showEmojiPanel) {
                    val panelHeight = lastKeyboardHeight
                    val emojiCategories = remember { linkedMapOf(
                        "Mặt cười" to listOf("😀","😁","😂","🤣","😃","😄","😅","😆","😉","😊","😋","😎","😍","🥰","😘","😗","😙","😚","🙂","🤗","🤩","🤔","🤨","😐","😑","😶","🙄","😏","😣","😥","😮","🤐","😯","😪","😫","😴","😌","😛","😜","😝","🤤","😒","😓","😔","😕","🙃","🤑","😲","🙁","😖","😞","😟","😤","😢","😭","😦","😧","😨","😩","🤯","😬","😰","😱","🥵","🥶","😳","🤪","😵","🥴","😠","😡","🤬","😈","👿","💀","☠️","💩","🤡","👹","👺","👻","👽","👾","🤖"),
                        "Cử chỉ" to listOf("👋","🤚","🖐️","✋","🖖","👌","🤌","🤏","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🤝","🙏","✍️","💪","🦾","🦿","🦵","🦶"),
                        "Trái tim" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟"),
                        "Đồ vật" to listOf("🎉","🎊","🎈","🎁","🎀","🏆","🥇","🥈","🥉","⚽","🏀","🏈","⚾","🎾","🏐","🎮","🎲","🎯","🎵","🎶","🎤","🎧","📱","💻","⌨️","📷","📹","🔔","📌","📎","✏️","📝","📁","🗑️"),
                        "Ăn uống" to listOf("🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥝","🍅","🥑","🍔","🍟","🍕","🌭","🌮","🍣","🍜","🍝","🍰","🎂","🍩","🍪","☕","🍵","🧃","🍺","🍷","🥤"),
                        "Thiên nhiên" to listOf("🌞","🌝","🌛","⭐","🌟","🌈","☁️","⛅","🌧️","⛈️","❄️","💧","🔥","🌊","🌸","🌺","🌻","🌹","🍀","🌿","🍃","🍂","🍁","🌴","🌵","🐶","🐱","🐭","🐰","🦊","🐻","🐼","🐨","🐯","🦁")
                    ) }
                    var activeCategory by remember { mutableStateOf(emojiCategories.keys.first()) }

                    Column(
                        modifier = Modifier.fillMaxWidth().height(panelHeight).background(Color.White)
                    ) {
                        // Category tabs
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            emojiCategories.keys.forEach { cat ->
                                val isActive = cat == activeCategory
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isActive) Color(0xFFF0EEFF) else Color.Transparent)
                                        .clickable { activeCategory = cat }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(emojiCategories[cat]!!.first(), fontSize = 20.sp)
                                }
                            }
                        }
                        // Emoji grid
                        val emojis = emojiCategories[activeCategory] ?: emptyList()
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(emojis.size) { idx ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            inputText += emojis[idx]
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(emojis[idx], fontSize = 25.sp)
                                }
                            }
                        }
                    }
                }
                // Audio recording panel
                if (showAudioPanel) {
                    val panelHeight = lastKeyboardHeight
                    // Mode: false = audio recording, true = speech-to-text
                    var audioModeText by remember { mutableStateOf(false) }
                    var isRecording by remember { mutableStateOf(false) }
                    var recordingDuration by remember { mutableIntStateOf(0) }
                    var sttText by remember { mutableStateOf("") }
                    var sttListening by remember { mutableStateOf(false) }
                    var sttHandsFree by remember { mutableStateOf(false) }
                    var sttAutoSend by remember { mutableStateOf(false) }

                    // MediaRecorder for audio file
                    val audioFile = remember { java.io.File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg") }
                    val recorder = remember { mutableStateOf<android.media.MediaRecorder?>(null) }

                    // Permission check — function to check at call time
                    fun checkAudioPerm() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (!granted) android.widget.Toast.makeText(context, "Cần quyền ghi âm", android.widget.Toast.LENGTH_SHORT).show()
                    }

                    // Recording timer
                    LaunchedEffect(isRecording) {
                        if (isRecording) {
                            recordingDuration = 0
                            while (isRecording) { kotlinx.coroutines.delay(1000); recordingDuration++ }
                        }
                    }

                    // SpeechRecognizer — no UI dialog
                    var sttPartialText by remember { mutableStateOf("") }
                    val speechRecognizer = remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }

                    DisposableEffect(Unit) {
                        onDispose { speechRecognizer.value?.destroy(); speechRecognizer.value = null }
                    }

                    fun startStt() {
                        if (!checkAudioPerm()) { permLauncher.launch(android.Manifest.permission.RECORD_AUDIO); return }
                        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
                            android.widget.Toast.makeText(context, "Thiết bị không hỗ trợ nhận diện giọng nói", android.widget.Toast.LENGTH_SHORT).show()
                            return
                        }

                        speechRecognizer.value?.destroy()
                        val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
                        sr.setRecognitionListener(object : android.speech.RecognitionListener {
                            override fun onResults(results: android.os.Bundle) {
                                val text = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                                if (text.isNotBlank()) {
                                    inputText = if (inputText.isBlank()) text else "$inputText $text"
                                }
                                sttListening = false
                                sttPartialText = ""
                                // Auto-send on hold-to-talk release
                                if (sttAutoSend && inputText.isNotBlank()) {
                                    container.socket.sendMessage(roomId = room.id, type = "text", content = inputText.trim(), replyTo = replyToMessage?.id)
                                    inputText = ""; replyToMessage = null; sttAutoSend = false
                                }
                            }
                            override fun onPartialResults(partial: android.os.Bundle) {
                                val text = partial.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                                sttPartialText = text
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

                    fun stopStt() {
                        speechRecognizer.value?.stopListening()
                        sttListening = false
                        sttPartialText = ""
                    }

                    fun cancelStt() {
                        speechRecognizer.value?.cancel()
                        speechRecognizer.value?.destroy()
                        speechRecognizer.value = null
                        sttListening = false
                        sttPartialText = ""
                        sttAutoSend = false
                        sttHandsFree = false
                    }

                    fun stopSttAndSend() {
                        sttAutoSend = true
                        sttHandsFree = false
                        speechRecognizer.value?.stopListening()
                        // onResults callback will fire and auto-send
                    }

                    fun startRecording() {
                        if (!checkAudioPerm()) {
                            permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            return
                        }
                        try {
                            // Delete old file if exists
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
                            android.util.Log.e("Audio", "Start recording failed", e)
                            android.widget.Toast.makeText(context, "Không thể ghi âm. Kiểm tra quyền micro.", android.widget.Toast.LENGTH_SHORT).show()
                            recorder.value = null
                            isRecording = false
                        }
                    }

                    fun stopRecordingAndSend() {
                        try {
                            try { recorder.value?.stop() } catch (_: Exception) {}
                            try { recorder.value?.release() } catch (_: Exception) {}
                            recorder.value = null
                            isRecording = false

                            // Must be longer than 1 second
                            if (recordingDuration < 1) {
                                audioFile.delete()
                                android.widget.Toast.makeText(context, "Tin nhắn quá ngắn", android.widget.Toast.LENGTH_SHORT).show()
                                return
                            }

                            // Upload the audio file
                            if (audioFile.exists() && audioFile.length() > 0) {
                                scope.launch {
                                    isUploading = true
                                    try {
                                        val bytes = audioFile.readBytes()
                                        val mimeType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "audio/ogg" else "audio/mp4"
                                        val ext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) "ogg" else "m4a"
                                        val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
                                        val filePart = MultipartBody.Part.createFormData("file", "voice_${System.currentTimeMillis()}.$ext", requestBody)
                                        val roomIdBody = room.id.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                        val res = withContext(Dispatchers.IO) { container.api.uploadFile(filePart, roomIdBody) }
                                        android.util.Log.d("Audio", "Upload result: success=${res.success} data=${res.data?.file_url}")
                                        if (res.success && res.data != null) {
                                            android.util.Log.d("Audio", "Sending socket message: room=${room.id} file=${res.data.file_url}")
                                            container.socket.sendMessage(
                                                roomId = room.id, type = "audio", content = "Tin nhắn thoại",
                                                fileUrl = res.data.file_url, fileName = res.data.file_name, fileSize = res.data.file_size
                                            )
                                            android.util.Log.d("Audio", "Socket message sent")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Audio", "Upload failed", e)
                                    }
                                    isUploading = false
                                    audioFile.delete()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Audio", "Stop recording failed", e)
                            recorder.value = null
                            isRecording = false
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelHeight)
                            .background(Color.White)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(PointerEventPass.Initial)
                                        val startX = down.changes.firstOrNull()?.position?.x ?: continue
                                        // Track and consume horizontal drags
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) break
                                            val dx = kotlin.math.abs(change.position.x - startX)
                                            if (dx > 10f) { change.consume() } // consume once horizontal drag detected
                                        }
                                    }
                                }
                            }
                    ) {
                        // Hands-free mode state
                        var handsFreeMode by remember { mutableStateOf(false) }
                        var audioAmplitude by remember { mutableFloatStateOf(0f) }
                        val amplitudeHistory = remember { mutableListOf<Float>() }
                        var previewPlaying by remember { mutableStateOf(false) }
                        val previewPlayer = remember { mutableStateOf<android.media.MediaPlayer?>(null) }

                        // Track amplitude + auto-stop at 20s
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
                                        if (handsFreeMode) {
                                            try { recorder.value?.stop(); recorder.value?.release() } catch (_: Exception) {}
                                            recorder.value = null; isRecording = false
                                        } else { stopRecordingAndSend() }
                                    }
                                }
                                audioAmplitude = 0f
                            }
                        }

                        val pulseScale by animateFloatAsState(
                            targetValue = if (isRecording) 1f + audioAmplitude * 0.5f else 1f,
                            animationSpec = androidx.compose.animation.core.tween(100), label = "pulse"
                        )
                        val ringAlpha by animateFloatAsState(
                            targetValue = if (isRecording && audioAmplitude > 0.05f) 0.3f + audioAmplitude * 0.4f else 0f,
                            animationSpec = androidx.compose.animation.core.tween(100), label = "ring"
                        )
                        val btnColor = if (audioModeText && sttListening) Color(0xFFE53935) else if (audioModeText) Color(0xFF4CAF50) else if (isRecording) Color(0xFF2196F3) else Color(0xFF2196F3)
                        val showWaveform = isRecording || (handsFreeMode && !isRecording && audioFile.exists())
                        val isHandsFreeReview = handsFreeMode && !isRecording && audioFile.exists()

                        // === TOP: Waveform bar (fixed at top) ===
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFF0F4FF),
                            modifier = Modifier.align(Alignment.TopCenter)
                                .fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                                .graphicsLayer { alpha = if (showWaveform) 1f else 0f }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Canvas(modifier = Modifier.weight(1f).height(24.dp)) {
                                    val bars = amplitudeHistory.takeLast(40)
                                    val barW = if (bars.isNotEmpty()) size.width / (bars.size * 2f) else 4f
                                    val centerY = size.height / 2
                                    bars.forEachIndexed { i, amp ->
                                        val h = (amp * 0.7f + 0.3f) * size.height * 0.8f
                                        drawLine(Color(0xFF2196F3), Offset(i * barW * 2 + barW / 2, centerY - h / 2),
                                            Offset(i * barW * 2 + barW / 2, centerY + h / 2), strokeWidth = barW * 0.8f,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                val m = String.format("%02d", recordingDuration / 60)
                                val s = String.format("%02d", recordingDuration % 60)
                                Text("$m:$s", fontSize = 14.sp, color = Color(0xFF555555))
                            }
                        }

                        // === FULL LAYOUT: text + button centered, tabs at bottom ===
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Instruction text
                            val instrText = when {
                                isRecording && !handsFreeMode -> "Thả tay để gửi"
                                sttListening && !sttHandsFree && sttPartialText.isNotBlank() -> sttPartialText
                                sttListening && !sttHandsFree -> "Đang nghe..."
                                sttListening && sttHandsFree && sttPartialText.isNotBlank() -> sttPartialText
                                sttListening && sttHandsFree -> "Đang nghe..."
                                isHandsFreeReview -> ""
                                audioModeText -> "Nhấn giữ để nói, chuyển thành văn bản"
                                else -> "Bấm giữ để ghi âm"
                            }
                            val instrText2 = when {
                                isRecording && !handsFreeMode -> "Vuốt sang phải để bật chế độ rảnh tay"
                                sttListening && !sttHandsFree -> "Vuốt sang phải để bật chế độ rảnh tay"
                                else -> ""
                            }

                            // Top spacer — pushes content to center
                            Spacer(Modifier.weight(1f))

                            // Text area — fixed 54dp window, text grows unbounded and aligns bottom
                            // Overflow clipped at TOP so latest text always visible
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
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

                            // Buttons: always same row structure
                            if (sttHandsFree && sttListening) {
                                // STT hands-free mode — cancel + stop/send
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.width(60.dp).clickable { cancelStt() }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Delete, "Hủy", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    Box(contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFFE53935).copy(alpha = 0.3f)))
                                        Box(
                                            modifier = Modifier.size(77.dp).clip(CircleShape).background(Color(0xFFE53935))
                                                .clickable { stopSttAndSend() },
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Default.Stop, "Gửi", tint = Color.White, modifier = Modifier.size(38.dp)) }
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {}
                                }
                            } else if (handsFreeMode && isRecording) {
                                // Hands-free audio recording
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.width(60.dp).clickable {
                                        try { recorder.value?.stop(); recorder.value?.release() } catch (_: Exception) {}
                                        recorder.value = null; isRecording = false; audioFile.delete()
                                        handsFreeMode = false; amplitudeHistory.clear(); recordingDuration = 0
                                    }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Delete, "Hủy", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    Box(contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(96.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = ringAlpha }
                                            .clip(CircleShape).background(Color(0xFFE53935).copy(alpha = 0.3f)))
                                        Box(
                                            modifier = Modifier.size(77.dp).clip(CircleShape).background(Color(0xFFE53935))
                                                .clickable {
                                                    try { recorder.value?.stop(); recorder.value?.release() } catch (_: Exception) {}
                                                    recorder.value = null; isRecording = false
                                                },
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Default.Stop, "Stop", tint = Color.White, modifier = Modifier.size(38.dp)) }
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {}
                                }
                            } else if (!isHandsFreeReview) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                    // Left: trash (visible when recording/stt active)
                                    Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                                        if (isRecording) Icon(Icons.Default.Delete, "Xóa", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    // Center: mic — hold-to-talk for both audio and STT modes
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isRecording || (sttListening && !sttHandsFree)) {
                                            Box(Modifier.size(96.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = if (isRecording) ringAlpha else 0.4f }
                                                .clip(CircleShape).background(btnColor.copy(alpha = 0.3f)))
                                        }
                                        Box(
                                            modifier = Modifier.size(77.dp).clip(CircleShape).background(btnColor)
                                                .pointerInput(audioModeText, handsFreeMode, sttHandsFree) {
                                                    // Both modes use hold-to-talk + swipe gestures
                                                    awaitPointerEventScope {
                                                        while (true) {
                                                            val down = awaitPointerEvent()
                                                            if (down.changes.any { it.pressed }) {
                                                                val startX = down.changes.first().position.x
                                                                if (audioModeText) startStt() else startRecording()
                                                                var swipedRight = false
                                                                while (true) {
                                                                    val event = awaitPointerEvent()
                                                                    val change = event.changes.firstOrNull() ?: break
                                                                    if (!change.pressed) {
                                                                        // Released
                                                                        if (swipedRight) {
                                                                            if (audioModeText) sttHandsFree = true
                                                                            else handsFreeMode = true
                                                                        } else {
                                                                            if (audioModeText) {
                                                                                // Hold-to-talk: stop STT and auto-send
                                                                                sttAutoSend = true
                                                                                stopStt()
                                                                            } else if (isRecording) {
                                                                                stopRecordingAndSend()
                                                                            }
                                                                        }
                                                                        break
                                                                    }
                                                                    val dragX = change.position.x - startX
                                                                    if (dragX > 80) swipedRight = true
                                                                    if (dragX < -80) {
                                                                        // Swipe left: cancel
                                                                        if (audioModeText) cancelStt()
                                                                        else {
                                                                            try { recorder.value?.stop(); recorder.value?.release() } catch (_: Exception) {}
                                                                            recorder.value = null; isRecording = false; audioFile.delete()
                                                                        }
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
                                                Icon(Icons.Default.Stop, "Dừng", tint = Color.White, modifier = Modifier.size(38.dp))
                                            } else {
                                                Icon(painterResource(R.drawable.ic_mic_phone), "Record", tint = Color.White, modifier = Modifier.size(38.dp))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    // Right: lock hint
                                    Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
                                        if (isRecording || (sttListening && !sttHandsFree)) Icon(Icons.Default.Lock, "Rảnh tay", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                                    }
                                }
                            } else {
                                // Hands-free review
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                    // Left: Xóa
                                    Box(modifier = Modifier.width(60.dp).clickable {
                                        previewPlayer.value?.release(); previewPlayer.value = null; previewPlaying = false
                                        audioFile.delete(); handsFreeMode = false; recordingDuration = 0; amplitudeHistory.clear()
                                    }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Delete, "Xóa", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    // Center: Gửi
                                    Box(contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF2196F3).copy(alpha = 0.15f)))
                                        Box(Modifier.size(77.dp).clip(CircleShape).background(Color(0xFF2196F3)).clickable {
                                            previewPlayer.value?.release(); previewPlayer.value = null; previewPlaying = false
                                            if (audioFile.exists() && audioFile.length() > 0) {
                                                scope.launch {
                                                    isUploading = true
                                                    try {
                                                        val bytes = audioFile.readBytes()
                                                        val requestBody = bytes.toRequestBody("audio/mp4".toMediaTypeOrNull())
                                                        val filePart = MultipartBody.Part.createFormData("file", "voice_${System.currentTimeMillis()}.m4a", requestBody)
                                                        val roomIdBody = room.id.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                                        val res = withContext(Dispatchers.IO) { container.api.uploadFile(filePart, roomIdBody) }
                                                        if (res.success && res.data != null) {
                                                            container.socket.sendMessage(roomId = room.id, type = "audio", content = "Tin nhắn thoại",
                                                                fileUrl = res.data.file_url, fileName = res.data.file_name, fileSize = res.data.file_size)
                                                        }
                                                    } catch (e: Exception) { android.util.Log.e("Audio", "Upload failed", e) }
                                                    isUploading = false; audioFile.delete(); handsFreeMode = false; recordingDuration = 0; amplitudeHistory.clear()
                                                }
                                            }
                                        }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.PlayArrow, "Gửi", tint = Color.White, modifier = Modifier.size(36.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(24.dp))
                                    // Right: Nghe lại
                                    Box(modifier = Modifier.width(60.dp).clickable {
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
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Không thể phát", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.GraphicEq, "Nghe lại", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
                                    }
                                }
                            }

                            // Bottom spacer — equal weight pushes content to center
                            Spacer(Modifier.weight(1f))

                            // Mode tabs at bottom — always present for stable layout, invisible when recording
                            val tabsVisible = !isRecording && !handsFreeMode && !sttListening
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                                    .graphicsLayer { alpha = if (tabsVisible) 1f else 0f },
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text("Gửi bản ghi âm", fontSize = 14.sp,
                                    color = if (!audioModeText) Color(0xFF2C3E50) else Color(0xFF7F8C8D),
                                    fontWeight = if (!audioModeText) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                        .border(if (!audioModeText) 1.5.dp else 1.dp, if (!audioModeText) Color(0xFF2C3E50) else Color(0xFFE0E0E0), RoundedCornerShape(20.dp))
                                        .then(if (tabsVisible) Modifier.clickable { audioModeText = false } else Modifier)
                                        .padding(horizontal = 16.dp, vertical = 8.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Gửi dạng văn bản", fontSize = 14.sp,
                                    color = if (audioModeText) Color(0xFF2C3E50) else Color(0xFF7F8C8D),
                                    fontWeight = if (audioModeText) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                        .border(if (audioModeText) 1.5.dp else 1.dp, if (audioModeText) Color(0xFF2C3E50) else Color(0xFFE0E0E0), RoundedCornerShape(20.dp))
                                        .then(if (tabsVisible) Modifier.clickable { audioModeText = true } else Modifier)
                                        .padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).onGloballyPositioned { coords ->
            overlayRootOffset = coords.localToRoot(Offset.Zero)
        }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF0F2F5))
        ) {
            // Pinned messages bar (hidden during in-chat search)
            if (pinnedMessages.isNotEmpty() && !showInChatSearch) {
                val pinBarBg = Color(0x33AFE1FF) // rgba(175, 225, 255, 0.2)
                Surface(
                    shadowElevation = 2.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, start = 6.dp, end = 6.dp)
                ) {
                    Column {
                        if (!showAllPinned) {
                            // Collapsed: show latest pinned
                            val latest = pinnedMessages.first()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(pinBarBg, RoundedCornerShape(6.dp))
                                    .clickable {
                                        scrollToAndHighlight(latest.id)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(painterResource(R.drawable.ic_message_pinned), null, modifier = Modifier.size(29.dp), tint = Color(0xFF488ECF))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        getDisplayName(latest.user_id, latest.username),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF3E1F91),
                                        maxLines = 1
                                    )
                                    Text(
                                        pinnedPreviewText(latest),
                                        fontSize = 14.sp,
                                        color = Color(0xFF7F8C8D),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (latest.type == "image" && latest.file_url != null) {
                                    Spacer(Modifier.width(8.dp))
                                    AsyncImage(
                                        model = UrlUtils.toFullUrl(latest.file_url),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                if (pinnedMessages.size > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFF0F2F5))
                                            .clickable { showAllPinned = true }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFFF6F61)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                if (pinnedMessages.size > 5) "5+" else "${pinnedMessages.size}",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                lineHeight = 10.sp,
                                                style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                                            )
                                        }
                                        Spacer(Modifier.width(2.dp))
                                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color(0xFF7F8C8D), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        } else {
                            // Expanded: header + scrollable list (max 3 visible)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(pinBarBg, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(painterResource(R.drawable.ic_message_pinned), null, modifier = Modifier.size(29.dp), tint = Color(0xFF488ECF))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${if (pinnedMessages.size > 5) "5+" else pinnedMessages.size} tin nhắn đã ghim",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF2C3E50),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { showAllPinned = false },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, "Collapse", tint = Color(0xFF7F8C8D), modifier = Modifier.size(20.dp))
                                }
                            }

                            // Scrollable pinned items (max 3 visible)
                            LazyColumn(modifier = Modifier.heightIn(max = 195.dp)) {
                                items(pinnedMessages, key = { it.id }) { pin ->
                                    var offsetX by remember { mutableStateOf(0f) }
                                    val animatedOffset by animateFloatAsState(offsetX, label = "swipe")

                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        // Push-to-top background (swipe right)
                                        if (offsetX > 0) {
                                            Box(
                                                modifier = Modifier.matchParentSize(),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                IconButton(onClick = {
                                                    // Move to top locally
                                                    val currentIndex = pinnedMessages.indexOfFirst { it.id == pin.id }
                                                    if (currentIndex > 0) {
                                                        val mutable = pinnedMessages.toMutableList()
                                                        val item = mutable.removeAt(currentIndex)
                                                        mutable.add(0, item)
                                                        pinnedMessages = mutable
                                                    }
                                                    offsetX = 0f
                                                    // Persist to database
                                                    scope.launch {
                                                        try {
                                                            container.api.pinToTop(mapOf("room_id" to room.id, "message_id" to pin.id))
                                                        } catch (_: Exception) {}
                                                    }
                                                }) {
                                                    Icon(painterResource(R.drawable.ic_push_to_top), "Top", tint = Color(0xFF3E1F91), modifier = Modifier.size(32.dp))
                                                }
                                            }
                                        }

                                        // Unpin background (swipe left)
                                        if (offsetX < 0) {
                                            Box(
                                                modifier = Modifier.matchParentSize(),
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                IconButton(onClick = {
                                                    container.socket.emit("unpin_message", JSONObject().apply {
                                                        put("room_id", room.id)
                                                        put("message_id", pin.id)
                                                    })
                                                    pinnedMessages = pinnedMessages.filter { it.id != pin.id }
                                                    if (pinnedMessages.size <= 1) showAllPinned = false
                                                }) {
                                                    Icon(painterResource(R.drawable.ic_unpin), "Unpin", tint = Color(0xFFFF6F61), modifier = Modifier.size(32.dp))
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .offset(x = animatedOffset.dp)
                                                .background(Color.White)
                                                .pointerInput(pin.id) {
                                                    detectHorizontalDragGestures(
                                                        onDragEnd = {
                                                            offsetX = when {
                                                                offsetX < -90f -> -80f
                                                                offsetX > 90f -> 80f
                                                                else -> 0f
                                                            }
                                                        },
                                                        onHorizontalDrag = { _, drag -> offsetX = (offsetX + drag * 0.7f).coerceIn(-120f, 120f) }
                                                    )
                                                }
                                                .clickable {
                                                    showAllPinned = false
                                                    scrollToAndHighlight(pin.id)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    getDisplayName(pin.user_id, pin.username),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF3E1F91),
                                                    maxLines = 1
                                                )
                                                Text(
                                                    pinnedPreviewText(pin),
                                                    fontSize = 14.sp,
                                                    color = Color(0xFF7F8C8D),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (pin.type == "image" && pin.file_url != null) {
                                                Spacer(Modifier.width(8.dp))
                                                AsyncImage(
                                                    model = UrlUtils.toFullUrl(pin.file_url),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFFF0F0F0))
                                }
                            }
                        }
                    }
                }
            }

            // Stranger banner
            val otherUserId = room.other_user?.id
            val isStrangerChat = room.type == "private" && otherUserId != null && otherUserId != currentUserId && !friendIds.contains(otherUserId)
            if (isStrangerChat && !isLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3E0))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Người lạ",
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                        modifier = Modifier
                            .background(Color(0xFFFF9800), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${room.other_user?.username ?: ""} chưa có trong danh sách bạn bè",
                        fontSize = 12.sp,
                        color = Color(0xFF795548),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF3E1F91))
                            .clickable {
                                scope.launch {
                                    try {
                                        val res = container.api.sendFriendRequest(mapOf("friend_id" to otherUserId))
                                        if (res.success) {
                                            Toast.makeText(context, "Đã gửi lời mời kết bạn", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, res.message ?: "Lỗi", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("FriendReq", "Send failed", e)
                                        Toast.makeText(context, "Không thể gửi lời mời: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Kết bạn", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF3E1F91))
                }
            } else {
                val dismissNestedScroll = remember {
                    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                        ): Offset {
                            if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.Drag
                                && available.y > 6f) {
                                hideKeyboardAndPanels()
                            }
                            return Offset.Zero
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { hideKeyboardAndPanels() })
                        }
                        .nestedScroll(dismissNestedScroll)
                ) {
                    // Precompute grouping: showDateSep + TimeDisplayConfig per message
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
                                nextCreatedAt = next?.created_at
                            )
                            info[msg.id] = Pair(showDateSep, timeConfig)
                        }
                        info
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val reversedMessages = messages.reversed()
                        val lastMsgId = messages.lastOrNull()?.id

                        items(reversedMessages, key = { it.id }) { msg ->
                            val isMine = msg.user_id == currentUserId
                            val status = if (isMine && msg.id == lastMsgId) "received" else null
                            val isSelected = selectedMessageIds.contains(msg.id)
                            val (msgShowDateSep, timeConfig) = messageDisplayInfo[msg.id]
                                ?: Pair(false, TimeDisplayConfig(true, TimePosition.INSIDE_BUBBLE, TimeStyle.NORMAL))

                            Column {
                            // Date separator — line + text + line
                            if (msgShowDateSep) {
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

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = if (multiSelectMode) Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedMessageIds = if (isSelected) selectedMessageIds - msg.id else selectedMessageIds + msg.id
                                    }
                                else Modifier.fillMaxWidth()
                            ) {
                                // Radio on left for friend's messages
                                if (multiSelectMode && !isMine) {
                                    MultiSelectRadio(isSelected)
                                    Spacer(Modifier.width(6.dp))
                                }

                                Box(modifier = Modifier.weight(1f)) {
                                    MessageBubble(
                                        message = msg,
                                        isSent = isMine,
                                        currentUserId = currentUserId,
                                        displayName = getDisplayName(msg.user_id, msg.username),
                                        replyDisplayName = getDisplayName(msg.reply_message?.user_id, msg.reply_message?.username),
                                        isHighlighted = msg.id == highlightedMessageId,
                                        searchHighlight = if (showInChatSearch) inChatSearchQuery else "",
                                        deliveryStatus = if (multiSelectMode) null else status,
                                        timeConfig = timeConfig,
                                        friendIds = friendIds,
                                        sentRequestIds = sentRequestIds,
                                        onNavigateToUser = { userId -> onUserWall(userId) },
                                        onSendFriendRequest = { contactId ->
                                            scope.launch {
                                                try {
                                                    val res = container.api.sendFriendRequest(mapOf("friend_id" to contactId))
                                                    if (res.success) {
                                                        sentRequestIds = sentRequestIds + contactId
                                                        android.widget.Toast.makeText(context, "Đã gửi lời mời kết bạn", android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        android.widget.Toast.makeText(context, res.message ?: "Lỗi", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (_: Exception) {
                                                    android.widget.Toast.makeText(context, "Không thể gửi lời mời", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onLongPress = { if (!multiSelectMode) selectedMessage = msg },
                                        onCallback = { isVideo -> if (isVideo) onVideoCall() else onVoiceCall() },
                                        onReplyClick = if (multiSelectMode) null else { replyId -> scrollToAndHighlight(replyId) },
                                        onSwipeReply = if (multiSelectMode) null else {{ if (msg.type != "recalled") replyToMessage = msg }},
                                        onClick = if (multiSelectMode) {{ selectedMessageIds = if (isSelected) selectedMessageIds - msg.id else selectedMessageIds + msg.id }} else null,
                                        onImageClick = if (multiSelectMode) null else {{ galleryForMessageId = msg.id }},
                                        showQuickReact = !multiSelectMode && msg.type != "call" && msg.type != "recalled" && (
                                            msg.type in listOf("image", "file", "video", "audio", "contact", "location") ||
                                            (!isMine && msg.id == (messages.lastOrNull { it.user_id != currentUserId }?.id)) ||
                                            (msg.type == "text" && msg.content?.any { Character.getType(it.code).let { t -> t == Character.OTHER_SYMBOL.toInt() || t == Character.SURROGATE.toInt() } || it.code > 0x2600 } == true)
                                        ),
                                        onReactionPillClick = { msgId ->
                                            reactionDetailMessageId = msgId
                                            showReactionDetail = true
                                            reactionDetailLoading = true
                                            reactionDetailData = null
                                            scope.launch {
                                                try {
                                                    val resp = container.api.getReactions(msgId)
                                                    if (resp.success && resp.data != null) {
                                                        reactionDetailData = resp.data
                                                    }
                                                } catch (_: Exception) {}
                                                reactionDetailLoading = false
                                            }
                                        },
                                        onQuickReact = { reactionType, btnCenter ->
                                            // Optimistic update
                                            val msgId = msg.id
                                            val oldReactions = msg.reactions ?: vn.chat9.app.data.model.ReactionData()
                                            if (reactionType == "__undo__") {
                                                messages = messages.map { m ->
                                                    if (m.id == msgId) m.copy(reactions = vn.chat9.app.data.model.ReactionData()) else m
                                                }
                                            } else {
                                                val oldSummary = (oldReactions.summary ?: emptyMap()).toMutableMap()
                                                val oldTotal = oldSummary.remove("total") ?: 0
                                                oldSummary[reactionType] = (oldSummary[reactionType] ?: 0) + 1
                                                oldSummary["total"] = oldTotal + 1
                                                val oldMy = (oldReactions.my_reactions ?: emptyMap()).toMutableMap()
                                                oldMy[reactionType] = (oldMy[reactionType] ?: 0) + 1
                                                messages = messages.map { m ->
                                                    if (m.id == msgId) m.copy(reactions = vn.chat9.app.data.model.ReactionData(
                                                        summary = oldSummary, my_reactions = oldMy, my_last_reaction = reactionType
                                                    )) else m
                                                }
                                                // Toast counter + floating emoji from toast center
                                                val emoji = reactionTypeToEmoji(reactionType)
                                                val screenCenterX = listState.layoutInfo.viewportSize.width / 2f
                                                val idx = messages.indexOfFirst { it.id == msgId }
                                                val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                                                val bubbleBottomY = if (itemInfo != null) (itemInfo.offset + itemInfo.size).toFloat() else btnCenter.y
                                                val toastCenterY = bubbleBottomY - with(density) { 80.dp.toPx() } + with(density) { 34.dp.toPx() }
                                                // Use actual button center (convert from root to overlay-local coords)
                                                val localBtnX = btnCenter.x - overlayRootOffset.x
                                                val localBtnY = btnCenter.y - overlayRootOffset.y
                                                val prevToast = reactionToast
                                                val newCount = if (prevToast != null && prevToast.messageId == msgId && System.currentTimeMillis() - prevToast.timestamp < 1000) prevToast.count + 1 else 1
                                                reactionToast = ReactionToast(msgId, emoji, newCount, screenCenterX, bubbleBottomY, localBtnX, localBtnY)
                                                // Spawn floating emoji from toast center
                                                if (reactionEffects.size < 15) {
                                                    reactionEffects.add(FloatingEmoji.create(effectIdCounter++, emoji, screenCenterX, toastCenterY))
                                                }
                                            }
                                            // API call
                                            scope.launch {
                                                try {
                                                    if (reactionType == "__undo__") {
                                                        container.api.removeReaction(vn.chat9.app.data.model.ReactionRemoveRequest(msgId))
                                                    } else {
                                                        container.api.addReaction(vn.chat9.app.data.model.ReactionRequest(msgId, reactionType))
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    )
                                }

                                // Radio on right for own messages
                                if (multiSelectMode && isMine) {
                                    Spacer(Modifier.width(6.dp))
                                    MultiSelectRadio(isSelected)
                                }
                            }
                            } // end Column (date sep + message row)
                        }
                        // Loading more indicator (at top of chat = end of reversed list)
                        if (isLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }

                    // Typing indicator (floating above input)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isTyping,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 46.dp, bottom = 4.dp)
                    ) {
                        Text(
                            "${getDisplayName(room.other_user?.id, typingUsername)} đang nhập...",
                            fontSize = 12.sp,
                            color = Color(0xFF7F8C8D),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            modifier = Modifier
                                .background(Color(0x63F0F2F5), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }

                    // Scroll to bottom button (reverseLayout: firstVisible > 3 means scrolled up)
                    val showScrollBtn by remember {
                        derivedStateOf {
                            messages.size > 5 && listState.firstVisibleItemIndex > 3
                        }
                    }
                    val scrollBtnAlpha by animateFloatAsState(
                        targetValue = if (showScrollBtn) 1f else 0f,
                        animationSpec = androidx.compose.animation.core.tween(300),
                        label = "scrollBtn"
                    )
                    if (scrollBtnAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp)
                                .graphicsLayer { alpha = scrollBtnAlpha; scaleX = scrollBtnAlpha; scaleY = scrollBtnAlpha }
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6F61).copy(alpha = 0.35f))
                                .clickable {
                                    scope.launch {
                                        listState.scrollToItem(0)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_scroll_down),
                                "Scroll to bottom",
                                tint = Color(0xFF3E1F91),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
        // Reaction effect overlay
        if (reactionEffects.isNotEmpty() || reactionToast != null || particleSystem.hasActive()) {
            ReactionEffectOverlay(
                effects = reactionEffects,
                toast = reactionToast,
                particleSystem = particleSystem,
                overlayRootOffset = overlayRootOffset,
                onEffectDone = { id -> reactionEffects.removeAll { it.id == id } },
                onToastClear = { reactionToast = null }
            )
        }
        }
    }

    // Long-press context menu overlay
    selectedMessage?.let { msg ->
        val isMine = msg.user_id == currentUserId
        val isPinned = pinnedMessages.any { it.id == msg.id }
        MessageContextMenu(
            message = msg,
            isMine = isMine,
            isPinned = isPinned,
            onDismiss = { selectedMessage = null },
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("message", msg.content ?: ""))
                Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show()
                selectedMessage = null
            },
            onRecall = {
                container.socket.emit("recall_message", JSONObject().put("message_id", msg.id))
                messages = messages.map {
                    if (it.id == msg.id) it.copy(type = "recalled", content = null) else it
                }
                selectedMessage = null
            },
            onDelete = {
                if (msg.type == "recalled") {
                    // Direct delete for recalled messages
                    container.socket.emit("delete_message", JSONObject().put("message_id", msg.id))
                    messages = messages.filter { it.id != msg.id }
                    selectedMessage = null
                } else {
                    // Enter multi-select with confirm for normal messages
                    multiSelectMode = true
                    selectedMessageIds = setOf(msg.id)
                    showDeleteConfirm = true
                    selectedMessage = null
                }
            },
            onPin = {
                if (isPinned) {
                    container.socket.emit("unpin_message", JSONObject().apply {
                        put("room_id", room.id)
                        put("message_id", msg.id)
                    })
                    Toast.makeText(context, "Đã bỏ ghim", Toast.LENGTH_SHORT).show()
                } else {
                    container.socket.emit("pin_message", JSONObject().apply {
                        put("room_id", room.id)
                        put("message_id", msg.id)
                    })
                    Toast.makeText(context, "Đã ghim", Toast.LENGTH_SHORT).show()
                }
                selectedMessage = null
            },
            onReply = {
                replyToMessage = msg
                selectedMessage = null
            },
            onForward = {
                forwardMessage = msg
                showForwardDialog = true
                selectedMessage = null
            },
            onMultiSelect = {
                multiSelectMode = true
                selectedMessageIds = setOf(msg.id)
                selectedMessage = null
            },
            onReact = { reactionType ->
                val msgId = msg.id
                selectedMessage = null
                // Optimistic update
                val oldReactions = msg.reactions ?: vn.chat9.app.data.model.ReactionData()
                val oldSummary = (oldReactions.summary ?: emptyMap()).toMutableMap()
                val oldTotal = oldSummary.remove("total") ?: 0
                oldSummary[reactionType] = (oldSummary[reactionType] ?: 0) + 1
                oldSummary["total"] = oldTotal + 1
                val oldMy = (oldReactions.my_reactions ?: emptyMap()).toMutableMap()
                oldMy[reactionType] = (oldMy[reactionType] ?: 0) + 1
                messages = messages.map { m ->
                    if (m.id == msgId) m.copy(reactions = vn.chat9.app.data.model.ReactionData(
                        summary = oldSummary, my_reactions = oldMy, my_last_reaction = reactionType
                    )) else m
                }
                // Toast counter + floating emoji from toast center
                val emoji = reactionTypeToEmoji(reactionType)
                val idx = messages.indexOfFirst { it.id == msgId }
                val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                if (itemInfo != null) {
                    val screenCenterX = listState.layoutInfo.viewportSize.width / 2f
                    val bubbleBottomY = (itemInfo.offset + itemInfo.size).toFloat()
                    val toastCenterY = bubbleBottomY - with(density) { 80.dp.toPx() } + with(density) { 34.dp.toPx() }
                    val isMine = msg.user_id == currentUserId
                    val btnOriginX = listState.layoutInfo.viewportSize.width * if (isMine) 0.75f else 0.25f
                    val btnOriginY = bubbleBottomY
                    reactionToast = ReactionToast(msgId, emoji, 1, screenCenterX, bubbleBottomY, btnOriginX, btnOriginY)
                    if (reactionEffects.size < 15) {
                        reactionEffects.add(FloatingEmoji.create(effectIdCounter++, emoji, screenCenterX, toastCenterY))
                    }
                }
                scope.launch {
                    try {
                        container.api.addReaction(vn.chat9.app.data.model.ReactionRequest(msgId, reactionType))
                    } catch (_: Exception) {}
                }
            }
        )
    }

    // Delete confirm dialog
    if (showDeleteConfirm && selectedMessageIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            text = {
                Text(
                    "Xóa ${selectedMessageIds.size} tin nhắn cho riêng bạn?",
                    fontSize = 16.sp,
                    color = Color(0xFF2C3E50)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedMessageIds.forEach { id ->
                        container.socket.emit("delete_message", JSONObject().put("message_id", id))
                    }
                    messages = messages.filter { it.id !in selectedMessageIds }
                    showDeleteConfirm = false
                    multiSelectMode = false
                    selectedMessageIds = emptySet()
                }) {
                    Text("Xóa", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Hủy", color = Color(0xFF7F8C8D))
                }
            }
        )
    }

    // Forward dialog
    if (showForwardDialog && forwardMessage != null) {
        ForwardDialog(
            message = forwardMessage!!,
            onDismiss = { showForwardDialog = false; forwardMessage = null },
            onForward = { selectedRooms, note, withCaption ->
                val msg = forwardMessage ?: return@ForwardDialog
                showForwardDialog = false
                forwardMessage = null
                scope.launch {
                    selectedRooms.forEach { targetRoom ->
                        val fwdContent = when {
                            msg.type == "image" && withCaption -> msg.content ?: ""
                            msg.type == "image" -> ""
                            msg.type == "audio" -> "Tin nhắn thoại"
                            msg.type in listOf("file", "video") -> msg.content ?: msg.file_name ?: ""
                            else -> msg.content ?: ""
                        }
                        // Send forwarded message first
                        container.socket.sendMessage(
                            roomId = targetRoom.id,
                            type = msg.type,
                            content = fwdContent,
                            fileUrl = msg.file_url,
                            fileName = msg.file_name,
                            fileSize = msg.file_size
                        )
                        // Then send note as a separate normal message
                        if (note.isNotBlank()) {
                            kotlinx.coroutines.delay(100)
                            container.socket.sendMessage(targetRoom.id, "text", note)
                        }
                        kotlinx.coroutines.delay(100)
                    }
                    Toast.makeText(context, "Đã chuyển tiếp", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Contact picker — fullscreen dialog (Zalo style)
    if (showContactPicker) {
        var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
        var selectedFriendId by remember { mutableStateOf<Int?>(null) }
        var includePhone by remember { mutableStateOf(true) }
        var cpSearch by remember { mutableStateOf("") }
        var cpLoading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            try {
                val res = container.api.getFriends("friends")
                if (res.success && res.data != null) friends = res.data
            } catch (_: Exception) {}
            cpLoading = false
        }

        fun sendContact() {
            val fId = selectedFriendId ?: return
            container.socket.sendMessage(roomId = room.id, type = "contact",
                content = fId.toString(), fileUrl = if (includePhone) "include_phone" else null)
            showContactPicker = false
            android.widget.Toast.makeText(context, "Đã gửi danh thiếp", android.widget.Toast.LENGTH_SHORT).show()
        }

        Dialog(
            onDismissRequest = { showContactPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            var cpSwipeX by remember { mutableFloatStateOf(0f) }
            val cpAnimOffset by animateFloatAsState(cpSwipeX, label = "cpSwipe")
            Surface(modifier = Modifier.fillMaxSize()
                .offset(x = cpAnimOffset.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (cpSwipeX > 120f) showContactPicker = false
                            cpSwipeX = 0f
                        },
                        onHorizontalDrag = { _, drag ->
                            cpSwipeX = (cpSwipeX + drag * 0.4f).coerceAtLeast(0f)
                        }
                    )
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(), color = Color.White) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header — back arrow + title + selected count
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showContactPicker = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Gửi danh thiếp", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("Đã chọn: ${if (selectedFriendId != null) 1 else 0}/1",
                                fontSize = 13.sp, color = Color.Gray)
                        }
                        // Send button (top right)
                        if (selectedFriendId != null) {
                            TextButton(onClick = { sendContact() }) {
                                Text("Gửi", fontSize = 16.sp, color = Color(0xFF3E1F91), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    // Search bar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(20.dp)).background(Color(0xFFF5F5F5))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = cpSearch, onValueChange = { cpSearch = it },
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                            modifier = Modifier.weight(1f),
                            decorationBox = @Composable { innerTextField ->
                                if (cpSearch.isEmpty()) Text("Tìm bạn bè", fontSize = 15.sp, color = Color(0xFFAAAAAA))
                                innerTextField()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Friend list with alphabetical groups
                    if (cpLoading) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(24.dp))
                        }
                    } else {
                        val filtered = friends.filter { cpSearch.isBlank() || it.username.contains(cpSearch, ignoreCase = true) }
                        val grouped = filtered.sortedBy { it.username.lowercase() }
                            .groupBy { it.username.first().uppercaseChar() }
                            .toSortedMap()

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            grouped.forEach { (letter, group) ->
                                // Group header
                                item(key = "header_$letter") {
                                    Text(
                                        letter.toString(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        color = Color.Gray,
                                        modifier = Modifier.fillMaxWidth().background(Color(0xFFF9F9F9))
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                }
                                // Friends in group
                                items(group, key = { it.id }) { friend ->
                                    val isSelected = selectedFriendId == friend.id
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { selectedFriendId = if (isSelected) null else friend.id }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Avatar
                                        val avatarUrl = UrlUtils.toFullUrl(friend.avatar)
                                        if (avatarUrl != null) {
                                            AsyncImage(model = avatarUrl, contentDescription = null,
                                                modifier = Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                        } else {
                                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3E1F91)), contentAlignment = Alignment.Center) {
                                                Text(friend.username.first().uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        // Name + phone
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(friend.username, fontSize = 16.sp, color = Color(0xFF2C3E50))
                                        }
                                        // Radio on right
                                        Box(modifier = Modifier.size(22.dp).clip(CircleShape)
                                            .border(2.dp, if (isSelected) Color(0xFF3E1F91) else Color(0xFFD0D0D0), CircleShape),
                                            contentAlignment = Alignment.Center) {
                                            if (isSelected) Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF3E1F91)))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom bar — phone toggle, centered
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFFF9F9F9))
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("Gửi kèm số điện thoại", fontSize = 14.sp, color = Color(0xFF555555))
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = includePhone, onCheckedChange = { includePhone = it },
                            modifier = Modifier.graphicsLayer { scaleX = 0.75f; scaleY = 0.65f },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF3E1F91))
                        )
                    }
                }
            }
        }
    }

    // Reaction detail bottom sheet
    if (showReactionDetail) {
        ReactionDetailSheet(
            data = reactionDetailData,
            isLoading = reactionDetailLoading,
            onDismiss = { showReactionDetail = false; reactionDetailData = null }
        )
    }

    // Full-screen image gallery — opens when a user taps any image bubble.
    // Collects every image message from the current conversation, centres
    // the pager on the clicked one, swipe-to-navigate + pinch-to-zoom.
    // Matches the web's viewImage() behaviour (see chat.js L4776).
    galleryForMessageId?.let { clickedId ->
        val imageMessages = remember(messages) { messages.filter { it.type == "image" } }
        if (imageMessages.isEmpty()) {
            LaunchedEffect(clickedId) { galleryForMessageId = null }
        } else {
            val clickedIndex = imageMessages.indexOfFirst { it.id == clickedId }
                .let { if (it >= 0) it else 0 }
            val galleryImages = remember(imageMessages) {
                imageMessages.mapNotNull { m ->
                    val url = UrlUtils.toFullUrl(m.file_url) ?: return@mapNotNull null
                    GalleryImage(
                        messageId = m.id,
                        url = url,
                        caption = m.content?.takeIf { c -> c.isNotBlank() && c != "null" },
                        fileName = m.file_name,
                        senderName = getDisplayName(m.user_id, m.username),
                        senderAvatar = m.avatar,
                        createdAt = m.created_at,
                    )
                }
            }
            if (galleryImages.isEmpty()) {
                LaunchedEffect(clickedId) { galleryForMessageId = null }
            } else {
                GalleryViewerDialog(
                    images = galleryImages,
                    initialIndex = clickedIndex,
                    onDismiss = { galleryForMessageId = null },
                    onReact = { msgId, reactionType ->
                        scope.launch {
                            try {
                                container.api.addReaction(vn.chat9.app.data.model.ReactionRequest(msgId, reactionType))
                            } catch (_: Exception) {}
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: Message, isSent: Boolean, currentUserId: Int = 0, displayName: String = message.username ?: "", replyDisplayName: String = message.reply_message?.username ?: "", isHighlighted: Boolean = false, searchHighlight: String = "", deliveryStatus: String? = null, timeConfig: TimeDisplayConfig = TimeDisplayConfig(true, TimePosition.INSIDE_BUBBLE, TimeStyle.NORMAL), friendIds: Set<Int> = emptySet(), sentRequestIds: Set<Int> = emptySet(), onSendFriendRequest: ((Int) -> Unit)? = null, onNavigateToUser: ((Int) -> Unit)? = null, onLongPress: () -> Unit = {}, onReplyClick: ((Int) -> Unit)? = null, onSwipeReply: (() -> Unit)? = null, onClick: (() -> Unit)? = null, showQuickReact: Boolean = false, onQuickReact: ((String, Offset) -> Unit)? = null, onReactionPillClick: ((Int) -> Unit)? = null, onCallback: ((Boolean) -> Unit)? = null, onImageClick: (() -> Unit)? = null) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Track reaction button center for effect spawn
    var reactBtnCenter by remember { mutableStateOf(Offset.Zero) }
    val highlightAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 1f else 0f,
        animationSpec = if (isHighlighted) androidx.compose.animation.core.tween(300) else androidx.compose.animation.core.tween(1500),
        label = "highlight"
    )
    val highlightScale by animateFloatAsState(
        targetValue = if (isHighlighted) 1.035f else 1f,
        animationSpec = if (isHighlighted) androidx.compose.animation.core.tween(300) else androidx.compose.animation.core.tween(1500),
        label = "scale"
    )
    val bubbleColor = if (isSent) Color(0xFFBEE8FD) else Color.White
    val alignment = if (isSent) Arrangement.End else Arrangement.Start
    val avatarUrl = if (!isSent) UrlUtils.toFullUrl(message.avatar) else null

    // Swipe to reply
    var swipeOffset by remember { mutableStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(swipeOffset, label = "swipe")
    val swipeThreshold = -80f

    Box(modifier = Modifier.fillMaxWidth()) {
        // Reply icon behind
        if (swipeOffset < -20f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    painterResource(R.drawable.ic_reply), "Reply",
                    tint = Color(0xFF3E1F91).copy(alpha = kotlin.math.min(1f, -swipeOffset / 80f)),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = highlightScale
                scaleY = highlightScale
            }
            .then(
                if (highlightAlpha > 0f) Modifier.background(
                    Color(0xFF8E44AD).copy(alpha = highlightAlpha * 0.15f),
                    RoundedCornerShape(12.dp)
                ) else Modifier
            ),
        horizontalArrangement = alignment,
        verticalAlignment = Alignment.Top
    ) {
        // Avatar at top-left of bubble for received messages
        if (!isSent) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = displayName,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (displayName.firstOrNull()?.uppercase() ?: "?"),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
        val hasAnyReactions = !message.reactions?.summary?.filterKeys { it != "total" }?.filterValues { it > 0 }.isNullOrEmpty()

        // Bubble + quick react wrapper
        Box(modifier = Modifier
            .padding(bottom = if ((showQuickReact && message.type != "recalled") || hasAnyReactions) 14.dp else 0.dp)
            .offset(x = animatedSwipeOffset.dp)
            .pointerInput(message.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset < swipeThreshold && message.type != "recalled") {
                            onSwipeReply?.invoke()
                        }
                        swipeOffset = 0f
                    },
                    onHorizontalDrag = { change, drag ->
                        if (drag < 0 || swipeOffset < 0f) {
                            swipeOffset = (swipeOffset + drag * 0.5f).coerceIn(-120f, 0f)
                            if (swipeOffset < 0f) change.consume()
                        }
                    }
                )
            }
        ) {
        val bubbleShape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .then(
                    if (highlightAlpha > 0f) Modifier.shadow(
                        elevation = (12 * highlightAlpha).dp,
                        shape = bubbleShape,
                        ambientColor = Color(0xFF8E44AD).copy(alpha = highlightAlpha * 0.5f),
                        spotColor = Color(0xFF8E44AD).copy(alpha = highlightAlpha * 0.7f)
                    ) else Modifier
                )
                .clip(bubbleShape)
                .background(
                    bubbleColor
                )
                .combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongPress,
                    onDoubleClick = { if (message.type != "recalled" && message.type != "call") onQuickReact?.invoke("love", reactBtnCenter) }
                )
                .padding(
                    start = 10.dp, end = 10.dp, top = 10.dp,
                    bottom = if (timeConfig.showTime && timeConfig.position == TimePosition.INSIDE_BUBBLE) 6.dp else 10.dp
                )
        ) {
            Column {
            // Reply preview (clickable → scroll to original) — hidden for recalled messages
            if (message.reply_message != null && message.type != "recalled") {
                val reply = message.reply_message
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x15000000))
                        .clickable { onReplyClick?.invoke(reply.id) }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .background(Color(0xFF3E1F91), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(replyDisplayName.ifEmpty { reply.username ?: "" }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3E1F91), maxLines = 1)
                        Text(
                            when (reply.type) {
                                "image" -> "[Hình ảnh]"
                                "file" -> reply.file_name ?: "[File]"
                                else -> reply.content ?: ""
                            },
                            fontSize = 11.sp, color = Color(0xFF7F8C8D), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (reply.type == "image" && reply.file_url != null) {
                        Spacer(Modifier.width(6.dp))
                        AsyncImage(
                            model = UrlUtils.toFullUrl(reply.file_url),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            when (message.type) {
                "image" -> {
                    val imageUrl = UrlUtils.toFullUrl(message.file_url)
                    if (imageUrl != null) {
                        Box {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = "Image",
                                modifier = Modifier
                                    .widthIn(max = 250.dp)
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (onImageClick != null)
                                            Modifier.clickable(
                                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                indication = null,
                                                onClick = { onImageClick() },
                                            )
                                        else Modifier
                                    ),
                                contentScale = ContentScale.Fit
                            )
                            if (timeConfig.showTime && timeConfig.position == TimePosition.MEDIA_OVERLAY) {
                                Text(
                                    text = DateUtils.formatTimeShort(message.created_at),
                                    fontSize = 9.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color(0x66000000), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    if (!message.content.isNullOrBlank() && message.content != "null") {
                        Spacer(Modifier.height(4.dp))
                        Text(text = emojiStyledText(message.content, 17.sp), fontSize = 17.sp)
                    }
                }
                "recalled" -> {
                    Text(
                        "Tin nhắn đã thu hồi",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
                "call" -> {
                    val callJson = try { JSONObject(message.content ?: "{}") } catch (_: Exception) { JSONObject() }
                    val reason = callJson.optString("reason")
                    val callType = callJson.optString("call_type")
                    val isVideo = callType == "video"
                    val duration = callJson.optInt("duration", 0)
                    val callerId = callJson.optInt("caller_id", 0)
                    val isCaller = callerId == currentUserId
                    val greenColor = Color(0xFF4CAF50)
                    val redColor = Color(0xFFF44336)
                    val grayColor = Color(0xFF757575)

                    val (iconRes, iconColor, callText) = when {
                        reason == "ended" && duration > 0 -> {
                            val m = (duration / 60).toString().padStart(2, '0')
                            val s = (duration % 60).toString().padStart(2, '0')
                            Triple(
                                if (isVideo) R.drawable.ic_call_video
                                else if (isCaller) R.drawable.ic_call_outgoing
                                else R.drawable.ic_call_incoming,
                                greenColor,
                                "$m:$s"
                            )
                        }
                        reason == "rejected" -> Triple(
                            if (isVideo) R.drawable.ic_call_video_rejected else R.drawable.ic_call_rejected,
                            if (isCaller) redColor else grayColor,
                            if (isCaller) "Đã từ chối" else "Bạn từ chối"
                        )
                        reason == "no_answer" -> Triple(
                            if (isVideo) R.drawable.ic_call_video_missed else R.drawable.ic_call_missed,
                            redColor,
                            if (isCaller) "Không trả lời" else "Cuộc gọi nhỡ"
                        )
                        reason == "busy" -> Triple(
                            if (isVideo) R.drawable.ic_call_video_missed else R.drawable.ic_call_missed,
                            redColor,
                            if (isCaller) "Máy bận" else "Cuộc gọi nhỡ"
                        )
                        reason == "ended" && duration == 0 -> Triple(
                            if (isCaller) {
                                if (isVideo) R.drawable.ic_call_video_canceled else R.drawable.ic_call_canceled
                            } else {
                                if (isVideo) R.drawable.ic_call_video_missed else R.drawable.ic_call_missed
                            },
                            if (isCaller) grayColor else redColor,
                            if (isCaller) "Bạn đã hủy" else "Cuộc gọi nhỡ"
                        )
                        else -> Triple(
                            if (isVideo) R.drawable.ic_call_video else R.drawable.ic_call_outgoing,
                            grayColor,
                            "Cuộc gọi"
                        )
                    }
                    val canCallback = !isCaller && (
                        reason == "no_answer" || reason == "busy" ||
                        (reason == "ended" && duration == 0)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (canCallback && onCallback != null) Modifier.clickable { onCallback(isVideo) } else Modifier
                    ) {
                        Icon(painterResource(iconRes), "call", tint = iconColor, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(callText, fontSize = 14.sp, color = iconColor)
                    }
                }
                "audio" -> {
                    VoiceMessageBubble(messageId = message.id, fileUrl = message.file_url, initialTranscript = message.transcript, isSent = isSent)
                }
                "file", "video" -> {
                    val fileName = message.file_name ?: "file"
                    val fileUrl = message.file_url
                    val fullUrl = UrlUtils.toFullUrl(fileUrl)
                    val lowerName = fileName.lowercase()
                    val isVideo = message.type == "video" || lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") || lowerName.endsWith(".mov")
                    val isPdf = lowerName.endsWith(".pdf")
                    val isAudioFile = lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") || lowerName.endsWith(".aac") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") || lowerName.endsWith(".flac") || lowerName.endsWith(".wma")
                    val ext = lowerName.substringAfterLast('.', "").uppercase()
                    val ctx = LocalContext.current

                    // Detect file type label + icon color
                    val typeLabel = when {
                        isPdf -> "PDF"
                        isVideo -> "VIDEO"
                        lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> "DOC"
                        lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") -> "XLS"
                        lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") -> "PPT"
                        lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") -> "ZIP"
                        ext.isNotEmpty() -> ext
                        else -> "FILE"
                    }
                    val iconBgColor = when {
                        isPdf -> Color(0xFFFF6D00)
                        isVideo -> Color(0xFF9C27B0)
                        isAudioFile -> Color(0xFF00897B)
                        lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> Color(0xFF1565C0)
                        lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") -> Color(0xFF2E7D32)
                        lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") -> Color(0xFFD84315)
                        else -> Color(0xFF546E7A)
                    }

                    fun openFile() {
                        if (fullUrl == null) return
                        val mime = when {
                            isPdf -> "application/pdf"
                            isVideo -> "video/*"
                            isAudioFile -> "audio/*"
                            else -> "*/*"
                        }
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(fullUrl), mime)) }
                        catch (_: Exception) { android.widget.Toast.makeText(ctx, "Không có ứng dụng mở file này", android.widget.Toast.LENGTH_SHORT).show() }
                    }

                    fun downloadFile() {
                        if (fullUrl == null) return
                        try {
                            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                            dm.enqueue(android.app.DownloadManager.Request(android.net.Uri.parse(fullUrl))
                                .setTitle(fileName).setDescription("9chat — Đang tải...")
                                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName))
                            android.widget.Toast.makeText(ctx, "Đang tải $fileName", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) { android.widget.Toast.makeText(ctx, "Không thể tải file", android.widget.Toast.LENGTH_SHORT).show() }
                    }

                    Column(modifier = Modifier.widthIn(max = 260.dp)) {
                        // PDF first page preview
                        if (isPdf && fullUrl != null) {
                            var pdfBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                            var pdfLoading by remember { mutableStateOf(true) }
                            LaunchedEffect(fullUrl) {
                                pdfBitmap = withContext(Dispatchers.IO) {
                                    try {
                                        val cacheFile = java.io.File(ctx.cacheDir, "pdf_preview_${message.id}.pdf")
                                        if (!cacheFile.exists()) {
                                            val res = okhttp3.OkHttpClient().newCall(
                                                okhttp3.Request.Builder().url(fullUrl).build()
                                            ).execute()
                                            cacheFile.outputStream().use { out -> res.body?.byteStream()?.copyTo(out) }
                                        }
                                        val fd = android.os.ParcelFileDescriptor.open(cacheFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                                        val renderer = android.graphics.pdf.PdfRenderer(fd)
                                        val page = renderer.openPage(0)
                                        val scale = 2
                                        val bmp = android.graphics.Bitmap.createBitmap(page.width * scale, page.height * scale, android.graphics.Bitmap.Config.ARGB_8888)
                                        bmp.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        page.close(); renderer.close(); fd.close()
                                        bmp
                                    } catch (e: Exception) {
                                        android.util.Log.e("PDF", "Preview failed", e)
                                        null
                                    }
                                }
                                pdfLoading = false
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().height(100.dp)
                                    .clip(RoundedCornerShape(8.dp)).background(Color.White)
                                    .clipToBounds()
                                    .clickable { openFile() },
                                contentAlignment = Alignment.TopCenter
                            ) {
                                if (pdfBitmap != null) {
                                    Image(
                                        bitmap = pdfBitmap!!.asImageBitmap(),
                                        contentDescription = "PDF preview",
                                        modifier = Modifier.fillMaxWidth(),
                                        contentScale = ContentScale.FillWidth,
                                        alignment = Alignment.TopCenter
                                    )
                                } else if (pdfLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(16.dp), strokeWidth = 2.dp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        // Video inline preview
                        if (isVideo && fullUrl != null) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(160.dp)
                                    .clip(RoundedCornerShape(8.dp)).background(Color.Black)
                                    .clickable { openFile() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painterResource(R.drawable.ic_play_outline), "Play", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(48.dp))
                                if (timeConfig.showTime && timeConfig.position == TimePosition.MEDIA_OVERLAY) {
                                    Text(
                                        text = DateUtils.formatTimeShort(message.created_at),
                                        fontSize = 9.sp,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .background(Color(0x66000000), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        // File info card — Zalo style
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { openFile() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Type icon badge (colored square with text)
                            Box(
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(iconBgColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(typeLabel, fontSize = if (typeLabel.length > 3) 9.sp else 11.sp,
                                    fontWeight = FontWeight.Bold, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            Spacer(Modifier.width(10.dp))
                            // Name + meta
                            Column(modifier = Modifier.weight(1f)) {
                                Text(fileName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val sizeText = if (message.file_size != null && message.file_size > 0) formatFileSize(message.file_size) else ""
                                Text("$typeLabel${if (sizeText.isNotEmpty()) " • $sizeText" else ""}", fontSize = 12.sp, color = Color.Gray)
                            }
                        }

                        // Action row + timestamp (same line)
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Download
                                Row(
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.68f)).clickable { downloadFile() }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Download, null, tint = Color(0xFF3E1F91), modifier = Modifier.size(21.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Tải về", fontSize = 12.sp, color = Color(0xFF3E1F91), fontWeight = FontWeight.Medium)
                                }
                                // Open/Preview/Play (PDF, video, audio)
                                if (isPdf || isVideo || isAudioFile) {
                                    Row(
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.68f)).clickable { openFile() }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isAudioFile || isVideo) painterResource(R.drawable.ic_play_outline)
                                            else painterResource(R.drawable.ic_file_open),
                                            null, tint = Color(0xFF3E1F91), modifier = Modifier.size(21.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            when {
                                                isPdf -> "Xem PDF"
                                                isAudioFile -> "Phát"
                                                else -> "Phát"
                                            },
                                            fontSize = 12.sp, color = Color(0xFF3E1F91), fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            // Timestamp inline with actions
                            if (timeConfig.showTime && timeConfig.position == TimePosition.INSIDE_BUBBLE) {
                                Text(
                                    text = DateUtils.formatTimeShort(message.created_at),
                                    fontSize = 10.sp,
                                    color = if (isSent) Color(0xFF006B8F) else Color(0xFF999999)
                                )
                            }
                        }
                    }
                }
                "contact" -> {
                    val cu = message.contact_user
                    val contactName = cu?.username ?: "Người dùng"
                    val contactAvatar = UrlUtils.toFullUrl(cu?.avatar)
                    val contactBio = cu?.bio?.takeIf { it != "null" }
                    val contactPhone = cu?.phone?.takeIf { it != "null" }
                    val ctx = LocalContext.current

                    val contactId = cu?.id
                    val isContactFriend = contactId != null && (friendIds.contains(contactId) || contactId == currentUserId)

                    Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                        // Contact info
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (contactAvatar != null) {
                                AsyncImage(model = contactAvatar, contentDescription = contactName,
                                    modifier = Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF3E1F91)), contentAlignment = Alignment.Center) {
                                    Text(contactName.first().uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(contactName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C3E50), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!contactBio.isNullOrBlank()) {
                                    Text(contactBio, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (!contactPhone.isNullOrBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable {
                                            try {
                                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,
                                                    android.net.Uri.parse("tel:$contactPhone")))
                                            } catch (_: Exception) {}
                                        }
                                    ) {
                                        Icon(Icons.Default.Phone, null, tint = Color(0xFF3E1F91), modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text(contactPhone, fontSize = 13.sp, color = Color(0xFF3E1F91))
                                    }
                                }
                            }
                        }
                        // Buttons + timestamp
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // When already friend: center Nhắn tin between left edge and timestamp
                            if (isContactFriend) Spacer(Modifier.weight(1f))
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF3E1F91).copy(alpha = 0.68f))
                                    .clickable { contactId?.let { onNavigateToUser?.invoke(it) } }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("Nhắn tin", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium) }
                            if (!isContactFriend && contactId != null) {
                                Spacer(Modifier.width(6.dp))
                                val alreadySent = sentRequestIds.contains(contactId)
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(if (alreadySent) Color(0xFFE0E0E0) else Color.White.copy(alpha = 0.68f))
                                        .then(if (!alreadySent) Modifier.clickable { onSendFriendRequest?.invoke(contactId) } else Modifier)
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text(if (alreadySent) "Đã gửi" else "Kết bạn", fontSize = 12.sp, color = if (alreadySent) Color(0xFF999999) else Color(0xFF3E1F91), fontWeight = FontWeight.Medium) }
                            }
                            // Weight spacer — pushes timestamp right; balances left spacer when friend
                            Spacer(Modifier.weight(1f))
                            if (timeConfig.showTime && timeConfig.position == TimePosition.INSIDE_BUBBLE) {
                                Text(
                                    text = DateUtils.formatMessageTime(message.created_at),
                                    fontSize = 10.sp,
                                    color = if (isSent) Color(0xFF006B8F) else Color(0xFF999999)
                                )
                            }
                        }
                    }
                }
                "location" -> {
                    val loc = try { org.json.JSONObject(message.content ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
                    val lat = loc.optDouble("lat", 0.0)
                    val lng = loc.optDouble("lng", 0.0)
                    val address = loc.optString("address", "${"%.6f".format(lat)}, ${"%.6f".format(lng)}")
                    val mapUrl = "https://www.google.com/maps?q=$lat,$lng"
                    // OSM tile with proper User-Agent
                    val tileZ = 15
                    val tileX = ((lng + 180) / 360 * (1 shl tileZ).toDouble()).toInt()
                    val latRad = Math.toRadians(lat)
                    val tileY = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * (1 shl tileZ).toDouble()).toInt()
                    val tileUrl = "https://tile.openstreetmap.org/$tileZ/$tileX/$tileY.png"
                    val ctx = LocalContext.current
                    val osmImageLoader = remember {
                        coil.ImageLoader.Builder(ctx)
                            .okHttpClient(okhttp3.OkHttpClient.Builder()
                                .addInterceptor { chain ->
                                    chain.proceed(chain.request().newBuilder()
                                        .header("User-Agent", "9chat-android/1.0 (contact: trungduc08@gmail.com)")
                                        .build())
                                }.build())
                            .build()
                    }

                    Column(
                        modifier = Modifier.clickable {
                            try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(mapUrl))) }
                            catch (_: Exception) {}
                        }
                    ) {
                        // Map thumbnail with pin
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(ctx)
                                    .data(tileUrl)
                                    .crossfade(true)
                                    .build(),
                                imageLoader = osmImageLoader,
                                contentDescription = "Map",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // Pin overlay
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(36.dp).offset(y = (-8).dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            address,
                            fontSize = 13.sp,
                            color = Color(0xFF2C3E50),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isSent) "Vị trí của bạn" else "Vị trí của $displayName",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
                else -> {
                    if (searchHighlight.isNotBlank() && message.content?.contains(searchHighlight, ignoreCase = true) == true) {
                        // Highlighted text for in-chat search
                        val text = message.content
                        val annotated = androidx.compose.ui.text.buildAnnotatedString {
                            var start = 0
                            val lower = text.lowercase()
                            val lowerQuery = searchHighlight.lowercase()
                            while (start < text.length) {
                                val idx = lower.indexOf(lowerQuery, start)
                                if (idx < 0) {
                                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF2C3E50))) { append(text.substring(start)) }
                                    break
                                }
                                if (idx > start) withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF2C3E50))) { append(text.substring(start, idx)) }
                                withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF2C3E50), background = Color(0x408E44AD), fontWeight = FontWeight.Bold)) {
                                    append(text.substring(idx, idx + searchHighlight.length))
                                }
                                start = idx + searchHighlight.length
                            }
                        }
                        Text(text = annotated, fontSize = 17.sp)
                    } else {
                        Text(
                            text = emojiStyledText(message.content ?: "", 17.sp),
                            fontSize = 17.sp,
                            color = Color(0xFF2C3E50)
                        )
                    }
                }
            }
            // Timestamp inside bubble (text, location, call — NOT file/video/contact, handled inline with actions)
            if (timeConfig.showTime && timeConfig.position == TimePosition.INSIDE_BUBBLE && message.type !in listOf("file", "video", "contact")) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = DateUtils.formatTimeShort(message.created_at),
                    fontSize = 10.sp,
                    color = if (isSent) Color(0xFF006B8F) else Color(0xFF999999),
                    modifier = Modifier.align(Alignment.End)
                )
            }
            } // end inner content Column
        }

        // Reactions + quick react at bottom-right of bubble
        val reactionData = message.reactions
        val summary = reactionData?.summary?.filterKeys { it != "total" }?.filterValues { it > 0 }
        val hasReactions = !summary.isNullOrEmpty()
        val myLastReaction = reactionData?.my_last_reaction
        val hasMyReaction = !reactionData?.my_reactions.isNullOrEmpty()
        val showHeartBtn = showQuickReact && message.type != "recalled"

        if (hasReactions || showHeartBtn) {
            var showReactPicker by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Reactions summary pill
                if (hasReactions) {
                    Row(
                        modifier = Modifier
                            .height(16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .combinedClickable(
                                onClick = { onReactionPillClick?.invoke(message.id) },
                                onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); showReactPicker = true }
                            )
                            .padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val noFontPadding = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                        val top3 = summary!!.entries.sortedByDescending { it.value }.take(3)
                        top3.forEach { (type, _) ->
                            Text(reactionTypeToEmoji(type), fontSize = 12.sp, lineHeight = 12.sp, style = noFontPadding)
                        }
                        val total = reactionData?.summary?.get("total") ?: summary.values.sum()
                        if (total > 0) {
                            Text("·", fontSize = 10.sp, lineHeight = 10.sp, color = Color(0xFF7F8C8D), style = noFontPadding)
                            Text("$total", fontSize = 10.sp, lineHeight = 10.sp, color = Color(0xFF7F8C8D), style = noFontPadding)
                        }
                    }

                    // My reaction pill (separate, only if I reacted)
                    if (hasMyReaction && myLastReaction != null) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .onGloballyPositioned { coords ->
                                    val pos = coords.localToRoot(Offset(coords.size.width / 2f, coords.size.height / 2f))
                                    reactBtnCenter = pos
                                }
                                .combinedClickable(
                                    onClick = { onQuickReact?.invoke(myLastReaction, reactBtnCenter) },
                                    onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); showReactPicker = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(reactionTypeToEmoji(myLastReaction), fontSize = 14.sp)
                        }
                    }
                }

                // Quick react heart button (hidden when my reaction pill is already shown)
                if (showHeartBtn && !hasMyReaction) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .onGloballyPositioned { coords ->
                                val pos = coords.localToRoot(Offset(coords.size.width / 2f, coords.size.height / 2f))
                                reactBtnCenter = pos
                            }
                            .combinedClickable(
                                onClick = { onQuickReact?.invoke("love", reactBtnCenter) },
                                onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); showReactPicker = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (myLastReaction != null) {
                            Text(reactionTypeToEmoji(myLastReaction), fontSize = 12.sp)
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_heart_react), "React",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Emoji picker dialog (centered on screen)
            if (showReactPicker) {
                Dialog(
                    onDismissRequest = { showReactPicker = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Dismiss on tap outside
                        Box(modifier = Modifier.fillMaxSize().clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { showReactPicker = false })

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = Color.White,
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val reactions = listOf("love" to "\u2764\uFE0F", "like" to "\uD83D\uDC4D", "haha" to "\uD83D\uDE02", "wow" to "\uD83D\uDE2E", "sad" to "\uD83D\uDE22", "angry" to "\uD83D\uDE21")
                                    reactions.forEach { (type, emoji) ->
                                        Text(
                                            emoji,
                                            fontSize = 32.sp,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .clickable {
                                                    showReactPicker = false
                                                    onQuickReact?.invoke(type, reactBtnCenter)
                                                }
                                                .padding(4.dp)
                                        )
                                    }
                                }
                            }
                            // Undo button (only if I already reacted)
                            if (hasMyReaction) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .shadow(4.dp, CircleShape)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .clickable {
                                            showReactPicker = false
                                            onQuickReact?.invoke("__undo__", reactBtnCenter)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painterResource(R.drawable.ic_slash_heart), "Undo",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        } // end bubble + quick react Box

        // Timestamp below bubble (audio)
        if (timeConfig.showTime && timeConfig.position == TimePosition.BELOW_BUBBLE) {
            Text(
                text = DateUtils.formatTimeShort(message.created_at),
                fontSize = 10.sp,
                color = Color(0xFF999999),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }

        // Delivery status below bubble (only for own messages)
        if (deliveryStatus != null) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                val statusIcon = when (deliveryStatus) {
                    "seen" -> R.drawable.ic_status_seen
                    "received" -> R.drawable.ic_status_received
                    else -> R.drawable.ic_status_sent
                }
                val statusText = when (deliveryStatus) {
                    "seen" -> "Đã xem"
                    "received" -> "Đã nhận"
                    else -> "Đã gửi"
                }
                val statusColor = when (deliveryStatus) {
                    "seen" -> Color(0xFF3E1F91)
                    else -> Color(0xFF7F8C8D)
                }
                Icon(
                    painterResource(statusIcon), null,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(statusText, fontSize = 11.sp, color = statusColor)
            }
        }
        } // end wrapper Column
    }
    } // end swipe Box
}

@Composable
private fun MultiSelectRadio(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color(0xFF3E1F91) else Color(0xFFE0E6ED)),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        } else {
            Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// Build AnnotatedString with emoji characters 25% larger than base font
@Composable
private fun emojiStyledText(text: String, baseFontSize: androidx.compose.ui.unit.TextUnit, baseColor: Color = Color(0xFF2C3E50)): androidx.compose.ui.text.AnnotatedString {
    val emojiSize = baseFontSize * 1.25f
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val charCount = Character.charCount(cp)
            // Check next char for variation selectors / ZWJ sequences
            var end = i + charCount
            while (end < text.length) {
                val nextCp = Character.codePointAt(text, end)
                if (nextCp == 0xFE0F || nextCp == 0xFE0E || nextCp == 0x200D ||
                    Character.getType(nextCp) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(nextCp) == Character.ENCLOSING_MARK.toInt() ||
                    (nextCp in 0x1F3FB..0x1F3FF) || // skin tone modifiers
                    (nextCp in 0xE0020..0xE007F) || // tag characters
                    nextCp == 0x20E3) { // combining enclosing keycap
                    end += Character.charCount(nextCp)
                } else break
            }
            val segment = text.substring(i, end)
            val isEmoji = Character.getType(cp).let { t ->
                t == Character.OTHER_SYMBOL.toInt() || t == Character.SURROGATE.toInt()
            } || cp > 0x2600
            if (isEmoji) {
                withStyle(androidx.compose.ui.text.SpanStyle(fontSize = emojiSize, letterSpacing = 4.sp)) { append(segment) }
            } else {
                withStyle(androidx.compose.ui.text.SpanStyle(fontSize = baseFontSize, color = baseColor)) { append(segment) }
            }
            i = end
        }
    }
}

private fun reactionTypeToEmoji(type: String): String = when (type) {
    "like" -> "👍"
    "love" -> "❤️"
    "haha" -> "😂"
    "wow" -> "😮"
    "sad" -> "😢"
    "angry" -> "😡"
    else -> type
}

// --- Reaction Effect (Zalo-style, zero-GC optimized) ---

// ParticleSystem — Structure of Arrays for zero GC in draw loop
private class ParticleSystem(val maxParticles: Int = 100) {
    val x      = FloatArray(maxParticles)
    val y      = FloatArray(maxParticles)
    val vx     = FloatArray(maxParticles)
    val vy     = FloatArray(maxParticles)
    val life   = IntArray(maxParticles)
    val color  = IntArray(maxParticles)
    val pSize  = FloatArray(maxParticles)
    val isRect = BooleanArray(maxParticles) // true=confetti rect, false=endpoint circle

    private fun findFreeSlot(): Int {
        for (i in 0 until maxParticles) { if (life[i] <= 0) return i }
        return -1
    }

    fun spawnConfetti(fromX: Float, fromY: Float, colors: IntArray) {
        repeat(30) {
            val slot = findFreeSlot(); if (slot == -1) return@repeat
            val angle = Math.random() * Math.PI * 2
            val speed = (3f + Math.random() * 3f).toFloat()
            x[slot] = fromX; y[slot] = fromY
            vx[slot] = (Math.cos(angle) * speed).toFloat()
            vy[slot] = (Math.sin(angle) * speed).toFloat()
            life[slot] = (25 + Math.random() * 15).toInt()
            color[slot] = colors[(Math.random() * colors.size).toInt()]
            pSize[slot] = (6f + Math.random() * 6f).toFloat()
            isRect[slot] = true
        }
    }

    fun spawnEndpointBurst(fromX: Float, fromY: Float, colors: IntArray) {
        repeat(20) {
            val slot = findFreeSlot(); if (slot == -1) return@repeat
            val angle = Math.random() * Math.PI * 2
            val speed = (2f + Math.random() * 3f).toFloat()
            x[slot] = fromX; y[slot] = fromY
            vx[slot] = (Math.cos(angle) * speed).toFloat()
            vy[slot] = (Math.sin(angle) * speed).toFloat()
            life[slot] = (30 + Math.random() * 5).toInt()
            color[slot] = colors[(Math.random() * colors.size).toInt()]
            pSize[slot] = (4f + Math.random() * 4f).toFloat()
            isRect[slot] = false
        }
    }

    fun update() {
        for (i in 0 until maxParticles) {
            if (life[i] <= 0) continue
            val gravity = if (isRect[i]) 0.20f else 0.15f
            vx[i] *= 0.98f
            vy[i] = vy[i] * 0.98f + gravity
            x[i] += vx[i]; y[i] += vy[i]
            life[i]--
        }
    }

    fun drawAll(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        for (i in 0 until maxParticles) {
            if (life[i] <= 0) continue
            val alpha = (life[i] * 8).coerceIn(0, 255) / 255f
            val c = Color(color[i]).copy(alpha = alpha)
            if (isRect[i]) {
                val rotation = (x[i] * 7 + y[i] * 3) % 360f
                withTransform({
                    translate(x[i], y[i])
                    rotate(rotation, Offset.Zero)
                }) {
                    drawRect(c, Offset(-pSize[i], -pSize[i] * 0.4f), androidx.compose.ui.geometry.Size(pSize[i] * 2, pSize[i] * 0.8f))
                }
            } else {
                drawCircle(c, pSize[i], Offset(x[i], y[i]))
            }
        }
    }

    fun hasActive(): Boolean { for (i in 0 until maxParticles) { if (life[i] > 0) return true }; return false }
}

// FloatingEmoji — time-based, no Animatable
private data class FloatingEmoji(
    val id: Long,
    val emoji: String,
    val p0: Offset,  // start (toast center)
    val p1: Offset,  // control 1 (scatter outward)
    val p2: Offset,  // control 2 (wobble mid-path)
    val p3: Offset,  // end (top of screen)
    val startTimeNanos: Long
) {
    companion object {
        const val DURATION_NS = 850L * 1_000_000L

        fun create(id: Long, emoji: String, cx: Float, cy: Float): FloatingEmoji {
            val scatterX = (-120..120).random().toFloat()
            val scatterY = (20..80).random().toFloat()
            val curveX = (-60..60).random().toFloat()
            val endDriftX = (-40..40).random().toFloat()
            return FloatingEmoji(id, emoji,
                p0 = Offset(cx, cy),
                p1 = Offset(cx + scatterX, cy + scatterY),
                p2 = Offset(cx + curveX, cy * 0.15f),
                p3 = Offset(cx + endDriftX, 20f),
                startTimeNanos = System.nanoTime()
            )
        }

        fun bezierAt(t: Float, p0: Offset, p1: Offset, p2: Offset, p3: Offset): Offset {
            val mt = 1f - t; val mt2 = mt * mt; val mt3 = mt2 * mt
            val t2 = t * t; val t3 = t2 * t
            return Offset(
                mt3 * p0.x + 3f * mt2 * t * p1.x + 3f * mt * t2 * p2.x + t3 * p3.x,
                mt3 * p0.y + 3f * mt2 * t * p1.y + 3f * mt * t2 * p2.y + t3 * p3.y
            )
        }
    }
}

private data class ReactionToast(
    val messageId: Int,
    val emoji: String,
    val count: Int,
    val x: Float,       // final center X (screen center)
    val y: Float,       // bubble bottom Y (toast renders at y - 80dp)
    val btnX: Float,    // reaction button X origin (overlay-local)
    val btnY: Float,    // reaction button Y origin (overlay-local)
    val timestamp: Long = System.currentTimeMillis()
)

private val CONFETTI_COLORS = intArrayOf(
    0xFFFF6B6B.toInt(), 0xFFFFD93D.toInt(), 0xFF6BCB77.toInt(),
    0xFF4D96FF.toInt(), 0xFFFF6BFF.toInt(), 0xFFFF9F43.toInt(),
    0xFF00D2D3.toInt(), 0xFFFF6348.toInt(), 0xFFA29BFE.toInt()
)
private val ENDPOINT_COLORS = intArrayOf(
    0xFFFFD93D.toInt(), 0xFFFF6B6B.toInt(), 0xFF6BCB77.toInt(),
    0xFF4D96FF.toInt(), 0xFFA29BFE.toInt()
)

@Composable
private fun ReactionEffectOverlay(
    effects: List<FloatingEmoji>,
    toast: ReactionToast?,
    particleSystem: ParticleSystem,
    overlayRootOffset: Offset,
    onEffectDone: (Long) -> Unit,
    onToastClear: () -> Unit
) {
    if (effects.isEmpty() && toast == null && !particleSystem.hasActive()) return

    val density = LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Toast animation state
    val toastAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val entranceProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val pulseScale = remember { androidx.compose.animation.core.Animatable(1f) }
    var toastRenderedCenter by remember { mutableStateOf(Offset.Zero) }
    var lastToastMessageId by remember { mutableIntStateOf(-1) }

    // Toast lifecycle: Appear → Confetti+Haptic → Pulse → Hold → FadeOut → Clear
    LaunchedEffect(toast?.count, toast?.timestamp) {
        if (toast == null) return@LaunchedEffect

        val isNewToast = toast.messageId != lastToastMessageId || toast.count == 1
        if (isNewToast) {
            lastToastMessageId = toast.messageId
            entranceProgress.snapTo(0f)
            toastAlpha.snapTo(0f)
            coroutineScope {
                launch { entranceProgress.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                launch { toastAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(150)) }
            }
        }

        // Confetti + haptic
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        particleSystem.spawnConfetti(toastRenderedCenter.x, toastRenderedCenter.y, CONFETTI_COLORS)

        // Pulse
        pulseScale.snapTo(1f)
        pulseScale.animateTo(1.25f, animationSpec = androidx.compose.animation.core.tween(80, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        pulseScale.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing))

        // Hold + fade out
        kotlinx.coroutines.delay(100)
        toastAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(200))
        entranceProgress.snapTo(0f)
        pulseScale.snapTo(1f)
        onToastClear()
    }

    // Single animation loop — ~60fps particle updates + emoji time tracking
    var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(effects.isNotEmpty() || particleSystem.hasActive()) {
        if (!effects.isNotEmpty() && !particleSystem.hasActive()) return@LaunchedEffect
        while (effects.isNotEmpty() || particleSystem.hasActive()) {
            kotlinx.coroutines.delay(16L)
            frameNanos = System.nanoTime()
            particleSystem.update()
        }
    }

    // Cleanup finished emojis — snapshot to avoid ConcurrentModificationException
    val nowNs = frameNanos
    val effectsSnapshot = effects.toList()
    val finishedIds = remember { mutableListOf<Long>() }
    finishedIds.clear()
    effectsSnapshot.forEach { emoji ->
        if (nowNs - emoji.startTimeNanos >= FloatingEmoji.DURATION_NS) {
            finishedIds.add(emoji.id)
        }
    }
    if (finishedIds.isNotEmpty()) {
        LaunchedEffect(finishedIds.toList()) {
            finishedIds.forEach { id ->
                // Spawn endpoint burst
                val emoji = effects.firstOrNull { it.id == id }
                if (emoji != null) {
                    val pos = FloatingEmoji.bezierAt(1f, emoji.p0, emoji.p1, emoji.p2, emoji.p3)
                    particleSystem.spawnEndpointBurst(pos.x, pos.y, ENDPOINT_COLORS)
                }
                onEffectDone(id)
            }
        }
    }

    // Toast counter rendering
    val displayToast = toast
    if (displayToast != null && toastAlpha.value > 0f) {
        val eP = entranceProgress.value
        val finalY = displayToast.y - with(density) { 80.dp.toPx() }
        val entranceScale = 0.3f + 0.7f * eP
        val currentTranslationX = (displayToast.btnX - displayToast.x) * (1f - eP)
        val currentTranslationY = displayToast.btnY * (1f - eP) + finalY * eP
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = currentTranslationX
                        translationY = currentTranslationY
                        alpha = toastAlpha.value
                        scaleX = pulseScale.value * entranceScale
                        scaleY = pulseScale.value * entranceScale
                    }
                    .size(68.dp)
                    .onGloballyPositioned { coords ->
                        val pos = coords.localToRoot(Offset(coords.size.width / 2f, coords.size.height / 2f))
                        toastRenderedCenter = Offset(pos.x - overlayRootOffset.x, pos.y - overlayRootOffset.y)
                    }
                    .clip(CircleShape)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White,
                                0.5f to Color.White.copy(alpha = 0.9f),
                                0.75f to Color.White.copy(alpha = 0.55f),
                                1.0f to Color.White.copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(displayToast.emoji, fontSize = 24.sp)
                    Text("${displayToast.count}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3E1F91), lineHeight = 13.sp)
                }
            }
        }
    }

    // Canvas — isolated graphicsLayer, zero-alloc draw
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {} // isolate layer — chat list not redrawn
    ) {
        // 1. Particles (zero allocation)
        particleSystem.drawAll(this)

        // 2. Floating emojis (time-based, use snapshot to avoid ConcurrentModificationException)
        val now = frameNanos
        effectsSnapshot.forEach { emoji ->
            val elapsed = now - emoji.startTimeNanos
            val t = (elapsed.toFloat() / FloatingEmoji.DURATION_NS).coerceIn(0f, 1f)

            val emojiScale = when {
                t < 0.15f -> t / 0.15f
                t > 0.75f -> 1f - ((t - 0.75f) / 0.25f)
                else -> 1f
            }
            val alpha = if (t > 0.6f) 1f - ((t - 0.6f) / 0.4f) else 1f
            val pos = FloatingEmoji.bezierAt(t, emoji.p0, emoji.p1, emoji.p2, emoji.p3)

            val measured = textMeasurer.measure(
                text = androidx.compose.ui.text.AnnotatedString(emoji.emoji),
                style = androidx.compose.ui.text.TextStyle(fontSize = 26.sp)
            )
            withTransform({
                translate(pos.x - measured.size.width / 2f, pos.y - measured.size.height / 2f)
                scale(emojiScale, emojiScale, Offset(measured.size.width / 2f, measured.size.height / 2f))
            }) {
                drawText(measured, alpha = alpha.coerceIn(0f, 1f))
            }
        }
    }
}

@Composable
private fun VoiceMessageBubble(messageId: Int, fileUrl: String?, initialTranscript: String? = null, isSent: Boolean) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableIntStateOf(0) }
    var currentPos by remember { mutableIntStateOf(0) }
    val player = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var transcript by remember { mutableStateOf(initialTranscript) }
    var showTranscript by remember { mutableStateOf(initialTranscript != null) }
    var isTranscribing by remember { mutableStateOf(false) }

    val fullUrl = vn.chat9.app.util.UrlUtils.toFullUrl(fileUrl)
    val scope = rememberCoroutineScope()

    DisposableEffect(fileUrl) {
        onDispose { player.value?.release(); player.value = null }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = player.value
            if (mp != null && mp.isPlaying) {
                currentPos = mp.currentPosition / 1000
                duration = mp.duration / 1000
                progress = if (mp.duration > 0) mp.currentPosition.toFloat() / mp.duration else 0f
            }
            kotlinx.coroutines.delay(200)
        }
    }

    fun togglePlay() {
        val mp = player.value
        if (mp != null && isPlaying) { mp.pause(); isPlaying = false; return }
        if (mp != null) { mp.seekTo(0); mp.start(); isPlaying = true; return }
        if (fullUrl == null) return
        try {
            val newMp = android.media.MediaPlayer()
            newMp.setDataSource(fullUrl)
            newMp.setOnPreparedListener { duration = it.duration / 1000; it.start(); isPlaying = true }
            newMp.setOnCompletionListener { isPlaying = false; progress = 0f; currentPos = 0; it.release(); player.value = null }
            newMp.setOnErrorListener { _, _, _ ->
                isPlaying = false
                android.widget.Toast.makeText(context, "Không thể phát", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            newMp.prepareAsync()
            player.value = newMp
        } catch (e: Exception) { android.util.Log.e("Voice", "Play failed", e) }
    }

    Column(modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play/pause button
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2196F3)).clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying) {
                    Icon(Icons.Default.Pause, "Pause", tint = Color.White, modifier = Modifier.size(26.dp))
                } else {
                    Icon(painterResource(R.drawable.ic_play_outline), "Play", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }

            Spacer(Modifier.width(8.dp))

            // Waveform + duration
            Column(modifier = Modifier.weight(1f)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                    val barCount = 30
                    val barWidth = size.width / (barCount * 2)
                    val centerY = size.height / 2
                    val playedBars = (progress * barCount).toInt()
                    for (i in 0 until barCount) {
                        val seed = (i * 7 + 3) % 11
                        val barH = (seed / 11f * 0.7f + 0.3f) * size.height * 0.8f
                        val color = if (i <= playedBars) Color(0xFF2196F3) else Color(0xFFBDBDBD)
                        val x = i * barWidth * 2 + barWidth / 2
                        drawLine(color, Offset(x, centerY - barH / 2), Offset(x, centerY + barH / 2),
                            strokeWidth = barWidth * 0.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                val displayTime = if (isPlaying || currentPos > 0) currentPos else duration
                Text("${String.format("%02d", displayTime / 60)}:${String.format("%02d", displayTime % 60)}",
                    fontSize = 11.sp, color = Color(0xFF7F8C8D))
            }

            Spacer(Modifier.width(8.dp))

            // Transcribe / toggle button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.68f))
                    .clickable {
                        if (transcript != null) {
                            // Toggle show/hide
                            showTranscript = !showTranscript
                        } else if (!isTranscribing) {
                            isTranscribing = true
                            scope.launch {
                                try {
                                    val res = container.api.transcribeMessage(
                                        vn.chat9.app.data.model.TranscribeRequest(messageId)
                                    )
                                    if (res.success && res.data != null) {
                                        transcript = res.data.transcript
                                        showTranscript = true
                                    } else {
                                        android.widget.Toast.makeText(context, res.message ?: "Lỗi chuyển đổi", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: retrofit2.HttpException) {
                                    val errBody = e.response()?.errorBody()?.string()
                                    val errMsg = try {
                                        org.json.JSONObject(errBody ?: "").optString("message", "HTTP ${e.code()}")
                                    } catch (_: Exception) { "HTTP ${e.code()}" }
                                    android.util.Log.e("Voice", "Transcribe HTTP ${e.code()}: $errBody", e)
                                    android.widget.Toast.makeText(context, errMsg, android.widget.Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    android.util.Log.e("Voice", "Transcribe failed", e)
                                    android.widget.Toast.makeText(context, "Không thể chuyển đổi: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                                isTranscribing = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF555555))
                } else if (transcript != null && showTranscript) {
                    Icon(Icons.Default.KeyboardArrowUp, "Ẩn", tint = Color(0xFF555555), modifier = Modifier.size(20.dp))
                } else {
                    Icon(painterResource(R.drawable.ic_translate), "Chuyển văn bản", tint = Color(0xFF555555), modifier = Modifier.size(20.dp))
                }
            }
        }

        // Transcript text
        if (transcript != null && showTranscript) {
            Text(
                transcript!!,
                fontSize = 17.sp,
                color = Color(0xFF2C3E50),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ActionPanelItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bgColor: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
            .width(64.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(bgColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = bgColor, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF555555), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun pinnedPreviewText(msg: Message): String {
    return when (msg.type) {
        "image" -> if (!msg.content.isNullOrBlank()) "Hình ảnh — ${msg.content}" else "Hình ảnh"
        "audio" -> "Tin nhắn thoại"
        "file", "video" -> "[${msg.type}] ${msg.file_name ?: ""}"
        "contact" -> "Danh thiếp"
        "location" -> "Vị trí"
        else -> msg.content ?: ""
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageContextMenu(
    message: Message,
    isMine: Boolean,
    isPinned: Boolean = false,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onMultiSelect: () -> Unit = {},
    onReact: (String) -> Unit = {}
) {
    // Check if recall is allowed (within 24h)
    // created_at can be "yyyy-MM-dd HH:mm:ss" (from PHP API) or ISO "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" (from WebSocket/mysql2)
    val canRecall = isMine && run {
        try {
            val dateStr = message.created_at
            val created = try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).parse(dateStr)
            } catch (_: Exception) {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(dateStr.replace(Regex("\\.\\d+Z$"), "").replace("Z", ""))
            }
            created != null && (System.currentTimeMillis() - created.time) < 24 * 60 * 60 * 1000
        } catch (_: Exception) { false }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { onDismiss() }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Highlighted message bubble
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    MessageBubble(message = message, isSent = isMine)
                }

                Spacer(Modifier.height(8.dp))

                // Emoji reaction row
                if (message.type != "recalled") {
                    Surface(
                        modifier = Modifier.padding(horizontal = 32.dp).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White,
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val reactions = listOf("like" to "👍", "love" to "❤️", "haha" to "😂", "wow" to "😮", "sad" to "😢", "angry" to "😡")
                            reactions.forEach { (type, emoji) ->
                                Text(
                                    emoji,
                                    fontSize = 24.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { onReact(type) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Action sheet — centered, 85% width
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(horizontal = 16.dp)
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
                        if (message.type == "recalled") {
                            // Recalled messages: only delete
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ContextMenuItem(Icons.Default.Delete, "Xóa", color = Color(0xFFFF3B30), onClick = onDelete)
                            }
                        } else {
                        // Row 1: Trả lời, Chuyển tiếp, Sao chép, Ghim
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ContextMenuItemPainter(painterResource(R.drawable.ic_reply), "Trả lời", onClick = onReply)
                            ContextMenuItem(Icons.Default.Shortcut, "Chuyển tiếp", onClick = onForward)
                            ContextMenuItem(Icons.Default.ContentCopy, "Sao chép", onClick = onCopy)
                            ContextMenuItem(Icons.Default.PushPin, if (isPinned) "Bỏ ghim" else "Ghim", onClick = onPin)
                        }

                        Spacer(Modifier.height(8.dp))

                        // Row 2: Chọn nhiều, Thu hồi (own), Chi tiết
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ContextMenuItemPainter(painterResource(R.drawable.ic_multi_select), "Chọn nhiều", onClick = onMultiSelect)
                            if (canRecall) {
                                ContextMenuItemPainter(painterResource(R.drawable.ic_recall), "Thu hồi", color = Color(0xFFFF6F61), onClick = onRecall)
                            }
                            ContextMenuItem(Icons.Default.Info, "Chi tiết", onClick = { /* TODO */ onDismiss() })
                            ContextMenuItem(Icons.Default.Delete, "Xóa", color = Color(0xFFFF3B30), onClick = onDelete)
                        }
                        } // end else (not recalled)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    color: Color = Color(0xFF2C3E50),
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun ContextMenuItemPainter(
    icon: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    color: Color = Color(0xFF2C3E50),
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = color, maxLines = 1)
    }
}

@Composable
fun ForwardDialog(
    message: Message,
    onDismiss: () -> Unit,
    onForward: (List<Room>, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container

    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var selectedRoomIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var note by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    val hasCaption = message.type == "image" && !message.content.isNullOrBlank()
    var includeCaption by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val res = container.api.getRooms()
            if (res.success && res.data != null) rooms = res.data
        } catch (_: Exception) {}
        isLoading = false
    }

    val filteredRooms = if (searchQuery.isBlank()) rooms else rooms.filter { room ->
        val name = if (room.type == "private") room.other_user?.displayName ?: "" else room.name ?: ""
        name.contains(searchQuery, ignoreCase = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            containerColor = Color.White,
            topBar = {
                // Header
                Surface(shadowElevation = 2.dp, color = Color.White) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Chia sẻ", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                                Text(
                                    "Đã chọn: ${selectedRoomIds.size}",
                                    fontSize = 13.sp,
                                    color = Color(0xFF7F8C8D)
                                )
                            }
                        }

                        // Search bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF0F2F5))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = Color(0xFF7F8C8D), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (searchQuery.isEmpty()) Text("Tìm kiếm", color = Color(0xFF7F8C8D), fontSize = 15.sp)
                                        innerTextField()
                                    }
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, "Clear", tint = Color(0xFF7F8C8D), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            },
            bottomBar = {
                // Bottom: message preview + input
                Surface(shadowElevation = 8.dp, color = Color.White) {
                    Column {
                        // Message preview card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF5F5F5))
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Image thumbnail for image messages
                                if (message.type == "image" && message.file_url != null) {
                                    AsyncImage(
                                        model = UrlUtils.toFullUrl(message.file_url),
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        when (message.type) {
                                            "image" -> "Hình ảnh"
                                            "file" -> message.file_name ?: "File"
                                            else -> message.content ?: ""
                                        },
                                        fontSize = 14.sp,
                                        color = Color(0xFF2C3E50),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Caption preview when toggle is on
                                    if (hasCaption && includeCaption) {
                                        Text(
                                            message.content ?: "",
                                            fontSize = 12.sp,
                                            color = Color(0xFF7F8C8D),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                            // Caption toggle for image messages
                            if (hasCaption) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { includeCaption = !includeCaption }
                                ) {
                                    // Mini toggle
                                    val thumbOffset by animateFloatAsState(if (includeCaption) 14f else 0f, label = "thumb")
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .height(16.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (includeCaption) Color(0xFF3E1F91) else Color(0xFFD0D0D0))
                                            .padding(2.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .offset(x = thumbOffset.dp)
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text("Hiện mô tả", fontSize = 12.sp, color = Color(0xFF7F8C8D))
                                }
                            }
                        }

                        // Input + send
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = note,
                                onValueChange = { note = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color(0xFF2C3E50)),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (note.isEmpty()) Text("Nhập tin nhắn", color = Color(0xFF7F8C8D), fontSize = 15.sp)
                                        innerTextField()
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            // Send button
                            val canSend = selectedRoomIds.isNotEmpty()
                            IconButton(
                                onClick = {
                                    if (canSend) {
                                        val selected = rooms.filter { selectedRoomIds.contains(it.id) }
                                        onForward(selected, note.trim(), includeCaption)
                                    }
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) Color(0xFF3E1F91) else Color(0xFFE0E6ED))
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    "Send",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            // Room list
            if (isLoading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF3E1F91), modifier = Modifier.size(32.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    // Section header
                    item {
                        Text(
                            "Gần đây",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF7F8C8D),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }

                    items(filteredRooms, key = { it.id }) { room ->
                        val roomName = if (room.type == "private") room.other_user?.displayName ?: "" else room.name ?: ""
                        val avatarUrl = if (room.type == "private") UrlUtils.toFullUrl(room.other_user?.avatar) else null
                        val isSelected = selectedRoomIds.contains(room.id)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedRoomIds = if (isSelected) selectedRoomIds - room.id else selectedRoomIds + room.id
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Avatar
                            if (avatarUrl != null) {
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = roomName,
                                    modifier = Modifier.size(48.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3E1F91)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(roomName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            // Name
                            Text(
                                roomName,
                                fontSize = 16.sp,
                                color = Color(0xFF2C3E50),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(Modifier.width(12.dp))

                            // Radio circle on right
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (isSelected)
                                            Modifier.background(Color(0xFF3E1F91))
                                        else
                                            Modifier
                                                .background(Color.White)
                                                .then(Modifier.shadow(0.dp).clip(CircleShape))
                                    )
                                    .then(
                                        if (!isSelected)
                                            Modifier.background(Color.White)
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color.Transparent)
                                            .then(
                                                Modifier.shadow(0.dp, CircleShape)
                                            )
                                    ) {
                                        // Empty circle border
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                                .background(Color.White)
                                                .padding(2.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE0E6ED))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(1.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionDetailSheet(
    data: vn.chat9.app.data.model.ReactionDetailResponse?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Dismiss overlay
            Box(modifier = Modifier.fillMaxSize().clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { onDismiss() })

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = Color.White,
                shadowElevation = 16.dp
            ) {
                if (isLoading || data == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = Color(0xFF3E1F91),
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                } else {
                    val summary = data.summary?.filterKeys { it != "total" }?.filterValues { it > 0 } ?: emptyMap()
                    val total = data.summary?.get("total") ?: summary.values.sum()
                    val tabs = mutableListOf("all" to total)
                    summary.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                        tabs.add(type to count)
                    }
                    var selectedTab by remember { mutableStateOf("all") }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Drag handle
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFFD0D0D0))
                            )
                        }

                        // Tab row
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tabs.forEach { (type, count) ->
                                item {
                                    val isSelected = selectedTab == type
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isSelected) Color(0xFFE8F0FE) else Color(0xFFF5F5F5))
                                            .clickable { selectedTab = type }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (type == "all") {
                                                Text(
                                                    "T\u1EA5t c\u1EA3",
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (isSelected) Color(0xFF1A73E8) else Color(0xFF666666)
                                                )
                                            } else {
                                                Text(reactionTypeToEmoji(type), fontSize = 16.sp)
                                            }
                                            Text(
                                                "$count",
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) Color(0xFF1A73E8) else Color(0xFF666666)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)

                        // User list
                        val filteredUsers = if (selectedTab == "all") {
                            data.users
                        } else {
                            data.users.filter { it.reactions.containsKey(selectedTab) }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
                        ) {
                            items(filteredUsers, key = { it.user_id }) { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Avatar
                                    AsyncImage(
                                        model = UrlUtils.toFullUrl(user.avatar),
                                        contentDescription = user.username,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFE0E6ED)),
                                        contentScale = ContentScale.Crop
                                    )

                                    Spacer(Modifier.width(10.dp))

                                    // Name
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            user.username,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF2C3E50),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Reaction emojis for this user
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        val userReactions = if (selectedTab == "all") {
                                            user.reactions
                                        } else {
                                            user.reactions.filterKeys { it == selectedTab }
                                        }
                                        userReactions.forEach { (type, count) ->
                                            Text(reactionTypeToEmoji(type), fontSize = 18.sp)
                                            if (count > 1) {
                                                Text(
                                                    "$count",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFF7F8C8D),
                                                    modifier = Modifier.padding(end = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
