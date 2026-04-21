package vn.chat9.app.navigation

/**
 * Single source of truth for every place the app can navigate to.
 *
 * Whenever a new screen is added, extend this sealed class. Whenever a new
 * entry point appears (new notification type, new deeplink, new banner, etc.),
 * it must describe its target as an `AppDestination` and go through
 * `MainActivity.navigateTo(dest)` — never set `screen`/`selectedRoom` state
 * directly. This guarantees every navigation path produces the same layout
 * regardless of where the user came from.
 *
 * See also:
 * - `DeepLinkRouter.parse(uri)` — maps `9chat://...` and web URLs to destinations
 * - `DeepLinkRouter.fromIntent(intent)` — maps FCM/call intent extras to destinations
 */
sealed class AppDestination {

    /** Back to the home tab grid (room list, contacts, etc.). */
    data class Home(val tab: HomeTab = HomeTab.MESSAGES) : AppDestination()

    /**
     * Open a chat room by its numeric id. Entry points never pass a partially
     * filled Room object; `navigateTo` fetches the canonical, enriched Room
     * via `rooms/detail.php` so payload shape is identical across callers.
     *
     * @param roomId         numeric id from the rooms table
     * @param scrollToMessageId optional — scroll to this message on open
     *                          (used by search results + pinned-message jumps)
     * @param autoCall       optional — immediately initiate a voice/video call
     *                       after the chat screen opens (used by "Gọi lại"
     *                       from missed-call notifications)
     */
    data class Chat(
        val roomId: Int,
        val scrollToMessageId: Int? = null,
        val autoCall: CallAction? = null
    ) : AppDestination()

    /** User wall (profile view + their stories + friend action). */
    data class Wall(val userId: Int) : AppDestination()

    /** Global search (users / messages / stories). */
    data class Search(val initialQuery: String? = null) : AppDestination()

    /** Profile edit screen (self-edit). */
    data object ProfileEdit : AppDestination()

    /** QR code scanner. */
    data object QrScanner : AppDestination()

    /** "Thêm bạn" screen — QR card + phone lookup + add-friend entry points. */
    data object AddFriend : AppDestination()

    /** Timeline / stories feed (same as Home(TIMELINE) but standalone route). */
    data object Timeline : AppDestination()

    /**
     * External share TO the app — fired when another app picks 9chat from its
     * share sheet. Holds one or more payloads to forward into a chat of the
     * user's choosing. See [SharePayload] for the supported kinds.
     *
     * Receiving flow: [DeepLinkRouter.fromIntent] detects ACTION_SEND /
     * ACTION_SEND_MULTIPLE, builds the list, hands it to `navigateTo` →
     * MainActivity opens the share-compose screen (room picker + caption +
     * send). The screen uploads any file payloads via `files/upload.php`
     * then emits socket messages, same path the in-app file picker uses.
     */
    data class ShareIncoming(val payloads: List<SharePayload>) : AppDestination()
}

/**
 * One piece of incoming share data. Either plain text (from a web page
 * "Share to..." link) or a file reference as a content:// URI string.
 */
sealed class SharePayload {
    data class Text(val content: String) : SharePayload()
    data class File(
        val uri: String,
        val mime: String?,
        val displayName: String?,
    ) : SharePayload()
}

enum class HomeTab(val index: Int) {
    MESSAGES(0),
    CONTACTS(1),
    DISCOVER(2),
    TIMELINE(3),
    ACCOUNT(4);

    companion object {
        fun fromIndex(i: Int) = entries.firstOrNull { it.index == i } ?: MESSAGES
    }
}

/** Action to trigger immediately after a Chat destination is opened. */
enum class CallAction { VOICE, VIDEO }
