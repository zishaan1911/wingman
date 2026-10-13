package com.wingman.assist

/**
 * Per-app config. The resource-ID fields are now OPTIONAL OVERRIDES — at runtime the app
 * auto-detects the chat UI (see AutoUi.kt) and caches what it finds (SelectorStore), so you
 * normally leave these null. Set one only if auto-detection gets something wrong on your app
 * version; use DatingAccessibilityService.dumpNodeTree() to discover the correct id.
 *
 * The one thing that MUST be correct is [packageName] (and the matching list in
 * accessibility_service_config.xml) — those are stable and identify the app.
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
