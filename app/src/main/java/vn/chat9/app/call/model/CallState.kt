package vn.chat9.app.call.model

/**
 * All states a call can be in. Transitions are driven by CallManager and
 * observed via StateFlow<CallState> by the UI layer.
 *
 * State machine (see CLAUDE-CALL-SYSTEM-V2.md §2.1):
 *
 *   IDLE → INIT → CONNECTING → RINGING → ACCEPTED → IN_CALL → ENDED
 *
 * Terminal branches:
 *   ENDED, REJECTED, BUSY, NO_ANSWER, NETWORK
 *
 * RECONNECTING may follow IN_CALL on transient ICE failure and either
 * returns to IN_CALL or transitions to NETWORK after the 10s budget.
 */
enum class CallState {
    IDLE,
    INIT,           // Caller tapped Gọi, local capture + offer being prepared
    CONNECTING,     // Offer sent, waiting for callee answer
    RINGING,        // Callee's phone is ringing (caller) or incoming UI shown (callee)
    ACCEPTED,       // Callee answered, ICE/DTLS still completing
    IN_CALL,        // ICE CONNECTED, media flowing
    RECONNECTING,   // Transient ICE drop — 10s max before transitioning to NETWORK
    ENDED,          // Normal hang-up
    REJECTED,       // Callee refused
    BUSY,           // Callee already in another call
    NO_ANSWER,      // 30s timeout
    NETWORK,        // ICE permanently failed / server disconnect
}
