package vn.chat9.app.navigation

import android.content.Intent
import android.net.Uri

/**
 * Parses external URIs and internal intents into [AppDestination] values.
 *
 * Extension rules:
 * - To support a new deep-link URI pattern, add a branch in [parseUri].
 * - To support a new intent-extra-driven entry (push notification variant,
 *   widget click, share target, etc.), add a branch in [fromIntent].
 *
 * **Never** add a new parsing codepath outside this file. The `9chat://` scheme
 * and `https://9chat.vn` host are both handled here so adding one URI pattern
 * covers both app-to-app deep links and web-to-app fallbacks.
 */
object DeepLinkRouter {

    /**
     * Parse an incoming [Intent] (typically from onCreate/onNewIntent) into a
     * destination. Handles three input kinds:
     *  1. Intent with [Intent.ACTION_VIEW] + URI (deep link)
     *  2. Intent with extras from FCM / call notifications (room_id, call_event, ...)
     *  3. Intent with the `tab` extra for home-tab switching
     */
    fun fromIntent(intent: Intent?): AppDestination? {
        if (intent == null) return null

        // 0. Inbound share from another app (picker hit our SEND / SEND_MULTIPLE
        //    intent filters in AndroidManifest). Build a ShareIncoming
        //    destination carrying every attached text + file payload so
        //    MainActivity can show the room picker.
        parseShareIntent(intent)?.let { return it }

        // 1. Explicit URI from VIEW action (deep link)
        intent.data?.let { uri ->
            parseUri(uri)?.let { return it }
        }

        // 2. Call-related extras (incoming call → chat with auto-call action)
        val callEvent = intent.getStringExtra("call_event")
        val callAction = intent.getStringExtra("call_action")
        val callTypeStr = intent.getStringExtra("call_type") // "audio" | "video"
        val roomIdStr = intent.getStringExtra("room_id")
        val roomIdInt = roomIdStr?.toIntOrNull()

        if (callAction == "auto_callback" && roomIdInt != null) {
            return AppDestination.Chat(
                roomId = roomIdInt,
                autoCall = if (callTypeStr == "video") CallAction.VIDEO else CallAction.VOICE
            )
        }

        // 3. Plain room_id extra (message notification tap)
        if (roomIdInt != null && callEvent == null) {
            return AppDestination.Chat(roomId = roomIdInt)
        }

        return null
    }

    /**
     * Parse a URI into a destination. Supports both custom-scheme and web URLs.
     *
     * Custom scheme: `9chat://<host>/<path?>`
     * Web fallback:  `https://9chat.vn/<path>`
     *
     * Patterns (both schemes map to the same destinations):
     *  - `9chat://room/{id}`               → Chat(id)
     *  - `9chat://user/{id}`               → Wall(id)
     *  - `9chat://search[?q={term}]`       → Search(term)
     *  - `9chat://timeline`                → Timeline
     *  - `9chat://qr`                      → QrScanner
     *  - `9chat://tab/{name}`              → Home(tab)
     *  - `https://9chat.vn/chat/{id}`      → Chat(id)
     *  - `https://9chat.vn/user/{id}`      → Wall(id)
     *  - `https://9chat.vn/search?q={term}`→ Search(term)
     */
    fun parseUri(uri: Uri): AppDestination? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val segments = uri.pathSegments

        // For custom 9chat:// URIs, the "host" acts as the target kind, and
        // path segments are the params. For web URLs, the first path segment
        // is the target kind and subsequent segments are params.
        val (kind, params) = when (scheme) {
            "9chat" -> uri.host?.lowercase() to segments
            "http", "https" -> {
                if (uri.host?.lowercase() != "9chat.vn" && uri.host?.lowercase() != "www.9chat.vn") {
                    return null
                }
                segments.firstOrNull()?.lowercase() to segments.drop(1)
            }
            else -> return null
        }

        return when (kind) {
            "room", "chat" -> {
                val id = params.firstOrNull()?.toIntOrNull() ?: return null
                AppDestination.Chat(roomId = id)
            }
            "user", "profile", "wall" -> {
                val id = params.firstOrNull()?.toIntOrNull() ?: return null
                AppDestination.Wall(userId = id)
            }
            "search" -> {
                val termFromPath = params.firstOrNull()
                val termFromQuery = uri.getQueryParameter("q")
                AppDestination.Search(initialQuery = termFromQuery ?: termFromPath)
            }
            "timeline", "stories" -> AppDestination.Timeline
            "qr" -> AppDestination.QrScanner
            "tab" -> {
                val name = params.firstOrNull()?.uppercase() ?: return null
                runCatching { HomeTab.valueOf(name) }.getOrNull()
                    ?.let { AppDestination.Home(it) }
            }
            else -> null
        }
    }

    /**
     * Resolve a share-in intent (ACTION_SEND / ACTION_SEND_MULTIPLE) into
     * a [AppDestination.ShareIncoming] carrying the attached text + file(s).
     *
     * Returns null for anything that isn't a share intent — callers can
     * fall through to the regular URI / extras branches.
     */
    private fun parseShareIntent(intent: Intent): AppDestination.ShareIncoming? {
        val action = intent.action ?: return null
        val payloads = mutableListOf<SharePayload>()

        when (action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                    payloads += SharePayload.Text(it)
                }
                @Suppress("DEPRECATION")
                val uri: android.net.Uri? =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                uri?.let {
                    payloads += SharePayload.File(
                        uri = it.toString(),
                        mime = intent.type,
                        displayName = null,
                    )
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris: List<android.net.Uri>? =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    } else {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                uris?.forEach { uri ->
                    payloads += SharePayload.File(
                        uri = uri.toString(),
                        mime = intent.type,
                        displayName = null,
                    )
                }
            }
            else -> return null
        }

        return if (payloads.isEmpty()) null else AppDestination.ShareIncoming(payloads)
    }
}
