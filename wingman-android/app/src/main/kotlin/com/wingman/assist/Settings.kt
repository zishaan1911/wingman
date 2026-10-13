package com.wingman.assist

import android.content.Context
import android.content.SharedPreferences

/**
 * All tunables, persisted in SharedPreferences and editable from DevPanelActivity.
 * Everything the "psychological" behaviour depends on is exposed here so you can dial
 * it in live without recompiling.
 */
class Settings(ctx: Context) {

    private val sp: SharedPreferences = ctx.getSharedPreferences("wingman", Context.MODE_PRIVATE)

    // --- master switches ---
    var autoReplyEnabled: Boolean
        get() = sp.getBoolean("autoReply", true)
        set(v) = sp.edit().putBoolean("autoReply", v).apply()

    var autoSwipeEnabled: Boolean
        get() = sp.getBoolean("autoSwipe", false)
        set(v) = sp.edit().putBoolean("autoSwipe", v).apply()

    var autonomy: AutonomyMode
        get() = runCatching { AutonomyMode.valueOf(sp.getString("autonomy", null) ?: "APPROVE_BIG_MOVES") }
            .getOrDefault(AutonomyMode.APPROVE_BIG_MOVES)
        set(v) = sp.edit().putString("autonomy", v.name).apply()

    // --- escalation policy ---
    var steerTowardDate: Boolean
        get() = sp.getBoolean("steerDate", true)
        set(v) = sp.edit().putBoolean("steerDate", v).apply()

    var minDepthToAskOut: Int
        get() = sp.getInt("minDepth", 8)
        set(v) = sp.edit().putInt("minDepth", v).apply()

    var minInterestToAskOut: Double
        get() = sp.getFloat("minInterest", 0.6f).toDouble()
        set(v) = sp.edit().putFloat("minInterest", v.toFloat()).apply()

    var readinessToAskOut: Double
        get() = sp.getFloat("readiness", 0.65f).toDouble()
        set(v) = sp.edit().putFloat("readiness", v.toFloat()).apply()

    /** Send personalized openers to brand-new matches (0-message threads). */
    var autoOpenerEnabled: Boolean
        get() = sp.getBoolean("autoOpener", true)
        set(v) = sp.edit().putBoolean("autoOpener", v).apply()

    /** Stop auto-replying to threads the decay detector rates COLD (saves effort). */
    var disengageWhenCold: Boolean
        get() = sp.getBoolean("disengageCold", false)
        set(v) = sp.edit().putBoolean("disengageCold", v).apply()

    /** Let the learning loop nudge tone toward what's getting you replies. */
    var banditEnabled: Boolean
        get() = sp.getBoolean("bandit", true)
        set(v) = sp.edit().putBoolean("bandit", v).apply()

    /**
     * Upper bound on auto-flirtation. The strategist picks per-match up to this ceiling.
     * Explicit content is never auto-sent regardless (always handed to you), and a
     * possible-minor signal forces CLEAN no matter what this is set to.
     */
    var flirtCeiling: Flirt
        get() = runCatching { Flirt.valueOf(sp.getString("flirtCeiling", null) ?: "SUGGESTIVE") }
            .getOrDefault(Flirt.SUGGESTIVE)
        set(v) = sp.edit().putString("flirtCeiling", v.name).apply()

    // --- strategy override: when non-null, forces tone/pace instead of the strategist ---
    var forceTone: Tone?
        get() = sp.getString("forceTone", null)?.let { runCatching { Tone.valueOf(it) }.getOrNull() }
        set(v) = sp.edit().apply { if (v == null) remove("forceTone") else putString("forceTone", v.name) }.apply()

    var forcePace: Pace?
        get() = sp.getString("forcePace", null)?.let { runCatching { Pace.valueOf(it) }.getOrNull() }
        set(v) = sp.edit().apply { if (v == null) remove("forcePace") else putString("forcePace", v.name) }.apply()

    // --- human-likeness ---
    /** Type replies character-by-character with realistic delays instead of pasting instantly. */
    var humanTyping: Boolean
        get() = sp.getBoolean("humanTyping", true)
        set(v) = sp.edit().putBoolean("humanTyping", v).apply()

    // --- swiping ---
    var dailySwipeCap: Int
        get() = sp.getInt("swipeCap", 60)
        set(v) = sp.edit().putInt("swipeCap", v).apply()

    /** How many panels (scrolls) to capture of a profile before deciding. */
    var profilePanels: Int
        get() = sp.getInt("profilePanels", 3)
        set(v) = sp.edit().putInt("profilePanels", v).apply()

    // --- persona ---
    var personaName: String
        get() = sp.getString("pName", "") ?: ""
        set(v) = sp.edit().putString("pName", v).apply()
    var personaVoice: String
        get() = sp.getString("pVoice", "dry humor, curious, low-key confident; texts short") ?: ""
        set(v) = sp.edit().putString("pVoice", v).apply()
    var personaBio: String
        get() = sp.getString("pBio", "") ?: ""
        set(v) = sp.edit().putString("pBio", v).apply()
    var personaBoundaries: String
        get() = sp.getString("pBound", "no explicit content; never agree to send money; never share home address") ?: ""
        set(v) = sp.edit().putString("pBound", v).apply()
    var personaWarmth: Double
        get() = sp.getFloat("pWarmth", 0.55f).toDouble()
        set(v) = sp.edit().putFloat("pWarmth", v.toFloat()).apply()

    // --- your type (swipe prefs) ---
    var swipeLooks: String
        get() = sp.getString("sLooks", "") ?: ""
        set(v) = sp.edit().putString("sLooks", v).apply()
    var swipePersonality: String
        get() = sp.getString("sPersonality", "") ?: ""
        set(v) = sp.edit().putString("sPersonality", v).apply()
    var swipeDealbreakers: String
        get() = sp.getString("sDealbreakers", "") ?: ""
        set(v) = sp.edit().putString("sDealbreakers", v).apply()
    var swipeSelectivity: Double
        get() = sp.getFloat("sSelect", 0.5f).toDouble()
        set(v) = sp.edit().putFloat("sSelect", v.toFloat()).apply()

    // --- notifications ---
    /** Push a phone notification for important events (approval needed, safety flag). */
    var notifyImportant: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    // --- auto-open the dating app when there's work ---
    /** When a scheduled reply is due but the app isn't open, launch it (send still only fires
     *  once the CORRECT chat is on screen — never the wrong one). */
    var autoOpenForSend: Boolean
        get() = sp.getBoolean("openForSend", true)
        set(v) = sp.edit().putBoolean("openForSend", v).apply()

    /** Auto-launch the swipe app to run the swipe loop unattended (more autonomous; off by default). */
    var autoOpenForSwipe: Boolean
        get() = sp.getBoolean("openForSwipe", false)
        set(v) = sp.edit().putBoolean("openForSwipe", v).apply()

    /** Which app to open for auto-swiping. */
    var swipeApp: App
        get() = runCatching { App.valueOf(sp.getString("swipeApp", null) ?: "TINDER") }.getOrDefault(App.TINDER)
        set(v) = sp.edit().putString("swipeApp", v.name).apply()

    fun persona() = Persona(personaName, personaVoice, personaBio, personaBoundaries, personaWarmth)
    fun swipePrefs() = SwipePrefs(swipeLooks, swipePersonality, swipeDealbreakers, swipeSelectivity)
}
