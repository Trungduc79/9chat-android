package vn.chat9.app.ui.chat

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.chat9.app.util.DateUtils
import vn.chat9.app.util.UrlUtils
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One image entry shown in the full-screen gallery.
 * Carries enough metadata to render the top bar (sender + time) and the
 * caption row without having to look the message back up from the chat.
 */
data class GalleryImage(
    val messageId: Int,
    val url: String,
    val caption: String?,
    val fileName: String? = null,
    val senderName: String,
    val senderAvatar: String? = null,
    val createdAt: String,
)

/**
 * Zalo-style full-screen image gallery:
 *
 *  ┌──────────────────────────────────────────┐
 *  │ ←  Avatar  Sender name       ↓    ⋮      │  — top bar
 *  │            relative time                  │
 *  │                                           │
 *  │             [IMAGE, zoomable]             │
 *  │                                           │
 *  │   ✓  caption text                         │  — caption below image
 *  │      …Xem thêm                            │
 *  │                                           │
 *  │  HD   ❤️  😮  👍              ↗         │  — idle bottom bar
 *  │  HD  [·][·][■][·][·]          ↗          │  — swiping bottom bar
 *  └──────────────────────────────────────────┘
 *
 * - Single-tap image toggles controls visibility.
 * - Swipe left/right navigates (when not zoomed); right swipe = newer? depends
 *   on array order. We render `images` in chronological order (oldest first,
 *   newest last) because that mirrors the chat scroll order — so swipe RIGHT
 *   goes to OLDER images and swipe LEFT goes to NEWER, matching the Zalo
 *   "vuốt sang phải để xem ảnh gửi trước đó" behaviour.
 * - Bottom bar swaps to thumbnail strip while the user is swiping, reverts to
 *   reactions/share 3s after the last swipe ends.
 */
@Composable
fun GalleryViewerDialog(
    images: List<GalleryImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onReact: ((messageId: Int, type: String) -> Unit)? = null,
) {
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val safeInitial = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial) { images.size }
    val currentPage = pagerState.currentPage
    val current = images.getOrNull(currentPage) ?: return

    var scale by remember(currentPage) { mutableFloatStateOf(1f) }
    var offsetX by remember(currentPage) { mutableFloatStateOf(0f) }
    var offsetY by remember(currentPage) { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }

    // Bottom-bar mode switching. When the pager is actively changing pages
    // we hide reactions/share and show the thumbnail strip. 3s after the
    // last page change the reactions come back.
    var showingThumbs by remember { mutableStateOf(false) }
    LaunchedEffect(currentPage) {
        if (images.size <= 1) return@LaunchedEffect
        if (currentPage != safeInitial || scale != 1f) {
            showingThumbs = true
            delay(3000)
            showingThumbs = false
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ── Pager + zoomable image fill the whole Box ──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                pageSpacing = 16.dp,
                userScrollEnabled = scale <= 1.01f,
            ) { page ->
                val item = images[page]
                val isCurrent = page == currentPage
                AsyncImage(
                    model = item.url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = if (isCurrent) scale else 1f,
                            scaleY = if (isCurrent) scale else 1f,
                            translationX = if (isCurrent) offsetX else 0f,
                            translationY = if (isCurrent) offsetY else 0f,
                        )
                        // Custom gesture instead of detectTransformGestures —
                        // the built-in version consumes every pointer event
                        // once past touch slop, including single-finger drags,
                        // which stole horizontal swipes from HorizontalPager
                        // and silently broke page navigation.
                        //
                        // Rules:
                        //   - 2+ fingers → pinch zoom + pan (always consume)
                        //   - 1 finger while zoomed (scale > 1) → pan
                        //     (consume so the zoomed image moves, not pager)
                        //   - 1 finger while not zoomed → DO NOT CONSUME,
                        //     HorizontalPager handles the swipe + tap
                        .pointerInput(page) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (!isCurrent) continue
                                    val pointers = event.changes.count { it.pressed }
                                    when {
                                        pointers >= 2 -> {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                                            scale = newScale
                                            if (newScale > 1f) {
                                                offsetX += panChange.x
                                                offsetY += panChange.y
                                            } else {
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                        pointers == 1 && scale > 1.01f -> {
                                            val panChange = event.calculatePan()
                                            offsetX += panChange.x
                                            offsetY += panChange.y
                                            event.changes.forEach { it.consume() }
                                        }
                                        // else: single finger, not zoomed —
                                        // pass-through to pager + tap detector
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(page) {
                            detectTapGestures(
                                onTap = { controlsVisible = !controlsVisible },
                                onDoubleTap = {
                                    if (!isCurrent) return@detectTapGestures
                                    if (scale > 1.01f) {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        },
                )
            }

            // ── Top bar: back / sender / time / download / more ──
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                GalleryTopBar(
                    senderName = current.senderName,
                    senderAvatar = current.senderAvatar,
                    timeLabel = remember(current.createdAt) { relativeDaysLabel(current.createdAt) },
                    onBack = onDismiss,
                    onDownload = { scope.launch { downloadImage(context, current.url, current.fileName) } },
                    onMore = { /* reserved — 3-dot menu */ },
                )
            }

            // ── Caption bar above bottom actions ──
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    val captionText = current.caption?.takeIf { it.isNotBlank() && it != "null" }
                    if (captionText != null) {
                        CaptionBlock(captionText)
                    }
                    // Bottom action row — swaps between reactions-and-share (idle)
                    // and HD + thumb strip + share (swiping).
                    GalleryBottomBar(
                        images = images,
                        currentIndex = currentPage,
                        showingThumbs = showingThumbs,
                        onThumbClick = { idx ->
                            scope.launch { pagerState.animateScrollToPage(idx) }
                        },
                        onReact = if (onReact != null) { type -> onReact(current.messageId, type) } else null,
                        onShare = {
                            scope.launch { shareImage(context, current.url, current.fileName) }
                        },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  TOP BAR
// ─────────────────────────────────────────────────────────────────
@Composable
private fun GalleryTopBar(
    senderName: String,
    senderAvatar: String?,
    timeLabel: String,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
        }
        // Avatar circle
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF555555)),
            contentAlignment = Alignment.Center,
        ) {
            if (!senderAvatar.isNullOrBlank()) {
                AsyncImage(
                    model = UrlUtils.toFullUrl(senderAvatar),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(
                    senderName.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                senderName,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                timeLabel,
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Default.FileDownload, contentDescription = "Tải xuống", tint = Color.White)
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, contentDescription = "Thêm", tint = Color.White)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  CAPTION BLOCK  ("✓ text … Xem thêm")
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CaptionBlock(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
        )
        if (!expanded && text.length > 120) {
            Text(
                "…Xem thêm",
                color = Color(0xFFBBBBBB),
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { expanded = true },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  BOTTOM BAR — idle (reactions) ↔ swiping (thumb strip)
// ─────────────────────────────────────────────────────────────────
@Composable
private fun GalleryBottomBar(
    images: List<GalleryImage>,
    currentIndex: Int,
    showingThumbs: Boolean,
    onThumbClick: (Int) -> Unit,
    onReact: ((String) -> Unit)?,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HdBadge()
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = showingThumbs,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween()) togetherWith fadeOut(tween()) },
            label = "bottomBarSwap",
        ) { isThumbs ->
            if (isThumbs && images.size > 1) {
                ThumbStrip(
                    images = images,
                    currentIndex = currentIndex,
                    onClick = onThumbClick,
                )
            } else {
                ReactionRow(onReact)
            }
        }
        Spacer(Modifier.width(8.dp))
        ShareButton(onShare)
    }
}

@Composable
private fun HdBadge() {
    Box(
        modifier = Modifier
            .border(1.dp, Color.White, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("HD", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReactionRow(onReact: ((String) -> Unit)?) {
    // Three quick reactions, Zalo-matching order. Falls back to no-op if
    // the caller didn't wire the API.
    val items = listOf(
        "love" to "❤️",
        "wow" to "😮",
        "like" to "👍",
    )
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        items.forEach { (type, emoji) ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
                    .clickable(enabled = onReact != null) { onReact?.invoke(type) },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ShareButton(onShare: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E88E5))
            .clickable { onShare() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Share, contentDescription = "Chia sẻ", tint = Color.White)
    }
}

@Composable
private fun ThumbStrip(
    images: List<GalleryImage>,
    currentIndex: Int,
    onClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // Auto-scroll to keep the active thumb visible when the page changes.
    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        itemsIndexed(images) { idx, img ->
            val active = idx == currentIndex
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (active) Modifier.border(2.dp, Color(0xFF1E88E5), RoundedCornerShape(4.dp))
                        else Modifier
                    )
                    .clickable { onClick(idx) },
            ) {
                AsyncImage(
                    model = img.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  HELPERS
// ─────────────────────────────────────────────────────────────────

/** "Vừa xong" / "5 phút trước" / "2 giờ trước" / "3 ngày trước" / "dd/MM/yyyy" */
private fun relativeDaysLabel(createdAt: String): String {
    if (createdAt.isBlank()) return ""
    val date = DateUtils.toMillis(createdAt).takeIf { it > 0 } ?: return createdAt
    val diffMs = System.currentTimeMillis() - date
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
    val days = TimeUnit.MILLISECONDS.toDays(diffMs)
    return when {
        minutes < 1 -> "Vừa xong"
        minutes < 60 -> "$minutes phút trước"
        hours < 24 -> "$hours giờ trước"
        days < 7 -> "$days ngày trước"
        else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(java.util.Date(date))
    }
}

private fun downloadImage(context: Context, url: String, hintedName: String?) {
    try {
        val uri = Uri.parse(url)
        val name = hintedName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "9chat_${System.currentTimeMillis()}.jpg"
        val request = DownloadManager.Request(uri)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(context, "Đang tải ảnh…", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "Không thể tải ảnh", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share the actual image file via the system chooser — NOT just the URL.
 * Downloads the image into cacheDir/shared/ (app-internal cache), wraps it
 * in a content:// URI via FileProvider, hands it off with ACTION_SEND +
 * an image MIME type. Receiving apps (Zalo, Messenger, Gallery, Gmail, etc.)
 * get the bytes directly and treat it as an image attachment.
 *
 * Runs download on IO dispatcher; the chooser launch must be on Main.
 * FileProvider authority is declared in AndroidManifest.xml as
 * ${applicationId}.fileprovider with paths defined in
 * res/xml/file_provider_paths.xml.
 */
private suspend fun shareImage(context: Context, url: String, hintedName: String?) {
    try {
        val file = withContext(Dispatchers.IO) {
            val name = sanitiseFileName(
                hintedName?.takeIf { it.isNotBlank() }
                    ?: Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
                    ?: "9chat_${System.currentTimeMillis()}.jpg"
            )
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Include a short hash of the URL in the filename so multiple
            // shares of different images don't collide on a common name
            // like "image.jpg".
            val target = File(sharedDir, "${url.hashCode().toString(16)}_$name")
            if (!target.exists() || target.length() == 0L) {
                URL(url).openStream().use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            }
            target
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
            ?.lowercase(Locale.ROOT)
        val mime = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "image/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Chia sẻ ảnh")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (e: Exception) {
        Toast.makeText(context, "Không thể chia sẻ ảnh", Toast.LENGTH_SHORT).show()
    }
}

/** Strip path separators / dangerous chars that might end up in a name. */
private fun sanitiseFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

private fun tween() = androidx.compose.animation.core.tween<Float>(durationMillis = 180)
