package com.wingman.assist

/**
 * Per-app UI selectors. These are the ONLY brittle, app-version-specific values in
 * the project. Tinder/Bumble don't ship stable, documented resource IDs, so you must
 * discover them on your device and keep them current across app updates.
 *
 * HOW TO FIND THEM:
 *   • Enable the service, open a chat, and call DatingAccessibilityService.dumpNodeTree()
 *     (wire it to a debug button or trigger) to log every node's viewIdResourceName /
 *     className / text to Logcat.
 *   • Or use Android Studio's Layout Inspector / uiautomatorviewer on the live screen.
 *
 * Any field left null falls back to heuristic matching in the service (e.g. "the
 * EditText near the bottom" for the message box), which is less reliable but works
 * as a starting point.
 */
data class AppProfile(
    val packageName: String,
    val app: App,

    // --- messaging screen ---
    /** resource-id of each chat message row/bubble container. */
    val messageRowId: String?,
    /** resource-id of the text view inside an INCOMING (their) bubble, if distinguishable. */
    val incomingTextId: String?,
    /** resource-id of the text view inside an OUTGOING (my) bubble, if distinguishable. */
    val outgoingTextId: String?,
    /** resource-id of the message input EditText. */
    val inputBoxId: String?,
    /** resource-id of the send button. */
    val sendButtonId: String?,
    /** resource-id of the match name header on the chat screen. */
    val matchNameId: String?,

    // --- swiping screen ---
    /** resource-id of the "like" / heart button (null → use a right-swipe gesture). */
    val likeButtonId: String?,
    /** resource-id of the "nope" / X button (null → use a left-swipe gesture). */
    val nopeButtonId: String?,
    /** resource-id of the bio/expand container, for scrolling the profile before capture. */
    val profileContainerId: String?,
) {
    companion object {
        // TODO: fill in real resource IDs for your installed app versions.
        val TINDER = AppProfile(
            packageName = "com.tinder",
            app = App.TINDER,
            messageRowId = null,      // e.g. "com.tinder:id/messageContent"
            incomingTextId = null,
            outgoingTextId = null,
            inputBoxId = null,        // e.g. "com.tinder:id/textEntryField"
            sendButtonId = null,
            matchNameId = null,
            likeButtonId = null,      // Tinder often has no stable button id → gesture swipe
            nopeButtonId = null,
            profileContainerId = null,
        )

        val BUMBLE = AppProfile(
            packageName = "com.bumble.app",
            app = App.BUMBLE,
            messageRowId = null,
            incomingTextId = null,
            outgoingTextId = null,
            inputBoxId = null,
            sendButtonId = null,
            matchNameId = null,
            likeButtonId = null,
            nopeButtonId = null,
            profileContainerId = null,
        )

        val ALL = listOf(TINDER, BUMBLE)
        fun forPackage(pkg: String): AppProfile? = ALL.firstOrNull { it.packageName == pkg }
    }
}
