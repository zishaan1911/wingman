package com.wingman.assist

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The dev panel: every psychological tunable, editable live. Writes to Settings
 * (SharedPreferences); the service reads settings fresh on each event, so changes
 * take effect immediately.
 */
class DevPanelActivity : Activity() {

    private lateinit var s: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
        }

        col.header("Autonomy")
        // cycles FULL_AUTO → APPROVE_BIG_MOVES → SUPERVISED
        val autonomyBtn = Button(this)
        fun renderAutonomy() { autonomyBtn.text = "Autonomy: ${s.autonomy}" }
        renderAutonomy()
        autonomyBtn.setOnClickListener {
            val next = AutonomyMode.values()[(s.autonomy.ordinal + 1) % AutonomyMode.values().size]
            s.autonomy = next; renderAutonomy()
        }
        col.addView(autonomyBtn)

        val autoReply = col.check("Auto-reply enabled", s.autoReplyEnabled) { s.autoReplyEnabled = it }
        val autoSwipe = col.check("Auto-swipe enabled", s.autoSwipeEnabled) { s.autoSwipeEnabled = it }

        col.header("Escalation")
        val steer = col.check("Steer toward a date", s.steerTowardDate) { s.steerTowardDate = it }
        val minDepth = col.field("Min messages before asking out", s.minDepthToAskOut.toString(), number = true)
        val minInterest = col.field("Min interest to ask out (0–1)", s.minInterestToAskOut.toString(), number = true)
        val readiness = col.field("Min readiness to ask out (0–1)", s.readinessToAskOut.toString(), number = true)

        col.header("Strategy override (blank = let the strategist decide per match)")
        val toneBtn = Button(this)
        fun renderTone() { toneBtn.text = "Force tone: ${s.forceTone?.name ?: "AUTO"}" }
        renderTone()
        toneBtn.setOnClickListener {
            val order = listOf<Tone?>(null) + Tone.values().toList()
            val i = order.indexOf(s.forceTone)
            s.forceTone = order[(i + 1) % order.size]; renderTone()
        }
        col.addView(toneBtn)
        val paceBtn = Button(this)
        fun renderPace() { paceBtn.text = "Force pace: ${s.forcePace?.name ?: "AUTO"}" }
        renderPace()
        paceBtn.setOnClickListener {
            val order = listOf<Pace?>(null) + Pace.values().toList()
            val i = order.indexOf(s.forcePace)
            s.forcePace = order[(i + 1) % order.size]; renderPace()
        }
        col.addView(paceBtn)

        col.header("You (persona)")
        val pName = col.field("Name", s.personaName)
        val pVoice = col.field("Voice (how you talk)", s.personaVoice)
        val pBio = col.field("Bio", s.personaBio)
        val pBound = col.field("Boundaries (never cross)", s.personaBoundaries)
        val pWarmth = col.field("Warmth floor (0–1)", s.personaWarmth.toString(), number = true)

        col.header("My type (so effort isn't wasted on the wrong people)")
        val sLooks = col.field("Looks I like (judged from photos)", s.swipeLooks)
        val sPers = col.field("Personality / lifestyle I want (from bio & prompts)", s.swipePersonality)
        val sDeal = col.field("Dealbreakers (any present → auto-pass)", s.swipeDealbreakers)
        val sSel = col.field("Selectivity (0 picky – 1 liberal)", s.swipeSelectivity.toString(), number = true)

        col.header("Swiping")
        val sCap = col.field("Daily swipe cap", s.dailySwipeCap.toString(), number = true)
        val sPanels = col.field("Profile panels for openers/photo audit (1–6)", s.profilePanels.toString(), number = true)
        col.check("Human-like typing (no instant paste)", s.humanTyping) { s.humanTyping = it }
        col.check("Notify me for important events", s.notifyImportant) { s.notifyImportant = it }

        col.header("Auto-open the app")
        col.check("Open the app to deliver a due reply", s.autoOpenForSend) { s.autoOpenForSend = it }
        col.check("Open the app to auto-swipe unattended", s.autoOpenForSwipe) { s.autoOpenForSwipe = it }
        val swipeAppBtn = Button(this)
        fun renderSwipeApp() { swipeAppBtn.text = "Auto-swipe app: ${s.swipeApp}" }
        renderSwipeApp()
        swipeAppBtn.setOnClickListener {
            val vals = App.values(); s.swipeApp = vals[(s.swipeApp.ordinal + 1) % vals.size]; renderSwipeApp()
        }
        col.addView(swipeAppBtn)

        col.header("Chances optimization")
        col.check("Send personalized openers to new matches", s.autoOpenerEnabled) { s.autoOpenerEnabled = it }
        col.check("Learning loop (nudge tone toward what works)", s.banditEnabled) { s.banditEnabled = it }
        col.check("Disengage from cold threads", s.disengageWhenCold) { s.disengageWhenCold = it }

        col.header("Flirtation (per-match, up to this ceiling)")
        col.addView(TextView(this).apply {
            text = "Strategist picks per match up to this cap. Explicit is always handed to you to approve; " +
                "a possible-minor signal forces CLEAN."
            setPadding(0, 0, 0, 8)
        })
        val flirtBtn = Button(this)
        fun renderFlirt() { flirtBtn.text = "Max flirtation: ${s.flirtCeiling}" }
        renderFlirt()
        flirtBtn.setOnClickListener {
            val vals = Flirt.values()
            s.flirtCeiling = vals[(s.flirtCeiling.ordinal + 1) % vals.size]; renderFlirt()
        }
        col.addView(flirtBtn)

        col.header("Analytics")
        val learn = LearningStore(this)
        val stats = TextView(this).apply { setPadding(0, 8, 0, 8) }
        fun renderStats() {
            val f = learn.funnel()
            stats.text = buildString {
                appendLine("Matches seen:    ${f.matches}")
                appendLine("Openers sent:    ${f.openersSent}")
                appendLine("Opener replies:  ${f.repliesReceived}  (${(f.openerReplyRate * 100).toInt()}%)")
                appendLine("Date proposals:  ${f.dateProposals}")
                appendLine("Off-app moves:   ${f.offAppMoves}")
                appendLine()
                appendLine("Reply rate by tone:")
                append(learn.toneReport())
            }
        }
        renderStats()
        col.addView(stats)
        col.addView(Button(this).apply { text = "Refresh stats"; setOnClickListener { renderStats() } })

        col.header("Profile photos (open your profile first, then audit)")
        val auditOut = TextView(this).apply { setPadding(0, 8, 0, 8) }
        col.addView(Button(this).apply {
            text = "Audit photos on screen"
            setOnClickListener {
                val svc = DatingAccessibilityService.instance
                if (svc == null) {
                    Toast.makeText(this@DevPanelActivity, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@DevPanelActivity, "Capturing & auditing…", Toast.LENGTH_SHORT).show()
                    svc.requestPhotoAudit { a ->
                        runOnUiThread {
                            auditOut.text = buildString {
                                appendLine(a.summary)
                                a.notes.forEach { appendLine("#${it.index}  ${(it.score * 100).toInt()}%  ${it.note}") }
                                if (a.bestOrder.isNotEmpty()) append("Suggested order: ${a.bestOrder.joinToString(", ")}")
                            }
                        }
                    }
                }
            }
        })
        col.addView(auditOut)

        col.addView(Button(this).apply {
            text = "SAVE"
            setOnClickListener {
                s.steerTowardDate = steer.isChecked
                s.autoReplyEnabled = autoReply.isChecked
                s.autoSwipeEnabled = autoSwipe.isChecked
                minDepth.text.toString().toIntOrNull()?.let { s.minDepthToAskOut = it }
                minInterest.text.toString().toDoubleOrNull()?.let { s.minInterestToAskOut = it.coerceIn(0.0, 1.0) }
                readiness.text.toString().toDoubleOrNull()?.let { s.readinessToAskOut = it.coerceIn(0.0, 1.0) }
                s.personaName = pName.text.toString()
                s.personaVoice = pVoice.text.toString()
                s.personaBio = pBio.text.toString()
                s.personaBoundaries = pBound.text.toString()
                pWarmth.text.toString().toDoubleOrNull()?.let { s.personaWarmth = it.coerceIn(0.0, 1.0) }
                s.swipeLooks = sLooks.text.toString()
                s.swipePersonality = sPers.text.toString()
                s.swipeDealbreakers = sDeal.text.toString()
                sSel.text.toString().toDoubleOrNull()?.let { s.swipeSelectivity = it.coerceIn(0.0, 1.0) }
                sCap.text.toString().toIntOrNull()?.let { s.dailySwipeCap = it }
                sPanels.text.toString().toIntOrNull()?.let { s.profilePanels = it.coerceIn(1, 6) }
                Toast.makeText(this@DevPanelActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    // --- tiny view DSL ---
    private fun LinearLayout.header(t: String) = addView(TextView(context).apply {
        text = t; textSize = 16f; setPadding(0, 36, 0, 8)
    })

    private fun LinearLayout.field(label: String, value: String, number: Boolean = false): EditText {
        addView(TextView(context).apply { text = label; setPadding(0, 16, 0, 0) })
        val et = EditText(context).apply {
            setText(value)
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        addView(et)
        return et
    }

    private fun LinearLayout.check(label: String, value: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val cb = CheckBox(context).apply {
            text = label; isChecked = value
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        addView(cb)
        return cb
    }
}
