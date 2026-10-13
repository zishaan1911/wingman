# Wingman — Android screen-reading dating assistant (prototype skeleton)

A personal Android app that reads your Tinder / Bumble conversations via the
**Accessibility Service** API, drafts a reply in your voice with the Groq API,
and auto-sends it at a **psychologically-chosen time** (never instant, mirrors
their rhythm, respects their active hours, jittered so it never looks mechanical).

> This is a working *skeleton*, not a finished app. The logic (timing engine,
> reply engine, conversation tracking, send/read plumbing) is real and wired up.
> The only parts you must fill in yourself are the **app-specific UI selectors**
> (the IDs of the chat list, message box, and send button inside Tinder/Bumble) —
> those change between app versions and can't be hardcoded reliably. See
> `AppProfiles.kt` and "Finding selectors" below.

---

## ⚠️ Read this first — risk & reality

- **Both Tinder and Bumble forbid automation in their ToS.** Auto-sending is the
  highest-risk behavior. Possible consequences: shadowban (your profile silently
  stops being shown) or a full account/device/phone-number ban, with no real appeal.
- The **timing engine is also your best anti-detection defense**: human-like,
  mirrored, jittered timing is exactly what *doesn't* trip bot detectors. Instant
  or clockwork-regular replies are what get flagged. So the "psychological timing"
  goal and the "don't get banned" goal point the same way — lean into it.
- Keep it to **your own account**. This is an offload tool, not a spam tool.
- Treat auto-send as something you supervise. The overlay gives you a kill-switch
  and a "hold / edit" window before anything goes out (configurable).

You accepted this tradeoff when you chose auto-send. Just keep it in view.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Tinder / Bumble (unmodified apps on your phone)               │
└───────────────┬──────────────────────────────────▲────────────┘
                │ reads screen (a11y node tree)      │ performs tap/type (a11y gestures)
                ▼                                     │
┌──────────────────────────────────────────────────────────────┐
│  DatingAccessibilityService                                     │
│   • extracts the open conversation (who said what)             │
│   • detects "their turn ended, my turn to reply"               │
│   • executes the send when the scheduler says go               │
└───────┬───────────────────────────────────┬──────────▲────────┘
        │ conversation snapshot              │ "send this text now"
        ▼                                     │          │
┌───────────────┐   ┌──────────────┐   ┌─────────────┐  │
│ConversationStore│→ │  ReplyEngine │   │SendScheduler│──┘
│ (msgs + times)  │   │  (Groq API)  │   │ (WorkManager)│
└───────┬────────┘   └──────┬───────┘   └──────▲──────┘
        │                    │ draft reply       │ fire at computed Instant
        │                    ▼                   │
        │            ┌────────────────┐          │
        └───────────▶│  TimingEngine  │──────────┘
                     │ computes WHEN  │
                     └────────────────┘
                              ▲
                     ┌────────┴────────┐
                     │  OverlayService │  status + kill-switch + hold/edit
                     └─────────────────┘
```

**Data flow per incoming message:**
1. `DatingAccessibilityService` sees the chat screen change, parses the visible
   conversation into `Message`s, hands them to `ConversationStore`.
2. When it's your turn, `ReplyEngine` asks Groq for a draft in your voice.
3. `TimingEngine` computes the ideal send `Instant` from their rhythm + active hours.
4. `SendScheduler` schedules a job for that instant. Overlay shows a countdown.
5. At fire time, the service re-opens that chat, types the draft, taps send
   (unless you held/killed it).

## File map

| File | Role |
|---|---|
| `TimingEngine.kt` | **The core you care about** — computes *when* to send. |
| `DatingAccessibilityService.kt` | Reads the screen, performs the send. |
| `AppProfiles.kt` | Per-app selectors (Tinder vs Bumble). **You fill these in.** |
| `ConversationStore.kt` | Remembers messages + timestamps per match (for mirroring/activity). |
| `ReplyEngine.kt` | Calls the Groq API to draft a reply in your voice. |
| `SendScheduler.kt` | Fires the send at the computed instant. |
| `OverlayService.kt` | Floating status chip + kill-switch + hold window. |
| `Models.kt` | `Message`, `Conversation`, `Sender`, `Persona`. |

## Setup

1. Open the project in **Android Studio** (it's a standard Gradle/Kotlin app).
2. Put your Groq API key in `local.properties` as `GROQ_API_KEY=gsk_...`
   (git-ignored) — `build.gradle.kts` wires it into `BuildConfig`. **Better:**
   stand up a tiny personal proxy and point `GroqClient.ENDPOINT` at it, so the
   key never ships inside the APK. See "Key safety" below.
3. Build & install on your phone (`./gradlew installDebug`).
4. On the phone: **Settings → Accessibility → Wingman → Enable**, and grant
   **"Display over other apps"** (overlay).
5. Fill in selectors in `AppProfiles.kt` (see below), reinstall.
6. Configure your `Persona` (bio, voice, boundaries) in `Models.kt` / settings.

## Finding selectors (the one manual step)

Tinder/Bumble don't expose stable resource IDs, and they change across versions,
so the skeleton ships with `TODO` placeholders. To discover the real ones:

- In Android Studio: **Tools → Layout Inspector**, or the standalone
  `uiautomatorviewer` (in the SDK's `tools/bin`), while the chat screen is open.
- Or dump the live tree from the service itself: the skeleton has a
  `dumpNodeTree()` helper you can call to log the current screen's nodes
  (`viewIdResourceName`, `className`, `text`) to Logcat. Open a Tinder chat,
  trigger the dump, and read off the message-row / input-box / send-button IDs.
- Put them in `AppProfiles.TINDER` / `AppProfiles.BUMBLE`.

Expect to re-check these after app updates — this is the brittle part of any
screen-reading approach, nothing to do with this design specifically.

## Key safety

Shipping an API key inside an Android APK means anyone who pulls the APK can
extract it. For a single-user personal build on your own phone it's a pragmatic
shortcut, but the clean pattern is a **thin personal proxy** (a tiny serverless
function) that holds the key and forwards to Groq. `GroqClient` is written
against a pluggable endpoint precisely so you can flip to that without touching
the rest of the app.

---

## LLM: Groq, end to end

One Groq key powers both jobs (`GroqClient.kt`):
- **Messaging** → `TEXT_MODEL` (default `llama-3.3-70b-versatile`) — fast + cheap,
  plenty for short chat replies.
- **Swiping** → `VISION_MODEL` (default `meta-llama/llama-4-scout-17b-16e-instruct`)
  — must be a multimodal model that accepts images.

Model IDs drift on Groq's side — verify the current IDs in the Groq console and
update the two constants in `GroqClient.kt` if needed. The client talks to Groq's
OpenAI-compatible Chat Completions endpoint, so swapping models or even providers
later is a one-line change.

## Optimizing your chances

Beyond timing + tone, the app works the whole funnel:

- **Personalized openers** (`OpenerEngine.kt`) — for a brand-new match (0 messages),
  the vision model reads their profile screenshot and writes a specific,
  reference-something first message instead of "hey". Biggest single lift to reply rate.
- **Learning loop + analytics** (`LearningStore.kt`) — logs outcomes (opener sent,
  reply sent, got a reply, date proposed, moved off-app), tracks **reply rate per tone**,
  and runs an epsilon-greedy bandit that nudges the tone toward what's actually earning
  *you* replies. The dev panel shows your funnel (matches → openers → replies → dates)
  so you can see where you're leaking.
- **Match memory** (`MatchMemory.kt`) — remembers durable facts each person mentioned
  (extracted by the strategist in the same read, no extra call) and feeds them back for
  natural callbacks ("how'd that exam go?"). One of the strongest rapport signals.
- **Decay detection** (`DecayDetector.kt`) — spots cooling threads (interest drop +
  slower/shorter replies) so you can disengage and refocus effort on live ones.
- **Photo auditor** (`PhotoAuditor.kt`) — open your profile, tap *Audit photos on screen*
  in the dev panel; the vision model scores each photo, suggests an order, and flags weak
  ones. Lead-photo A/B testing runs through the funnel: set a variant, let it run, compare
  match rate (photo reordering itself is a manual step — reliable auto-reorder isn't
  feasible via accessibility).

All of these are toggleable in the dev panel.

## Robustness & realism

- **Human-like typing** — replies are typed character-by-character with realistic,
  jittered delays (pauses after spaces/punctuation, occasional longer pauses) instead of
  pasted instantly. Instant paste is a classic bot tell; this reads as real typing and
  reduces detection risk. Toggle in the dev panel.
- **Multi-panel profile capture** — before a swipe/opener decision the app screenshots the
  profile, scrolls, and screenshots again (configurable 1–6 panels) so the vision model
  sees all photos + the full bio, not just the first screen.
- **Persistence** — conversations and message timestamps are saved to disk (JSON), so the
  timing/mirroring/decay models keep their history across restarts instead of resetting.
  (Lightweight JSON rather than an ORM; swap for Room later if the data grows.)
- **Reliability** — scheduled sends are persisted and re-armed on service (re)connect, so a
  pending reply survives the process being killed; the overlay runs as a foreground service
  so Android doesn't reap it. (WorkManager isn't used because only the live accessibility
  service can perform the type/tap — it's already a persistent, re-bound process, so
  persisting the queue there is the correct fix.)

## Flirtation & boundaries

The strategist reads how flirty each match is being and matches it per-match, up to a
**ceiling you set in the dev panel** (`CLEAN → FLIRTY → SUGGESTIVE`). Two hard rules are
built in and not configurable:

- **Explicit content is never auto-sent.** If a thread turns explicitly sexual, the bot
  drafts a reply and hands it to you in the overlay to approve/edit/send — you remain the
  one who sends it.
- **Adult-only.** Any signal that a match may be under 18 forces `CLEAN` and blocks all
  suggestive content.

## Screenshots & privacy

Screenshots are **never written to your phone** (no gallery, no disk, no MediaStore) — the
bytes exist only in RAM long enough to be analysed, bitmaps are recycled immediately. They
are sent to Groq over the network for analysis (inherent to a cloud vision model); if you
want zero cloud exposure of images, that needs an on-device vision model (not yet built).

## Your "type" — don't waste effort on the wrong people

The dev panel has a **My type** section with three fields:
- **Looks I like** — judged from the photos.
- **Personality / lifestyle I want** — judged from the bio & prompts.
- **Dealbreakers** — any present is an automatic pass, no matter how good the rest is.

The swipe engine weighs looks *and* personality together and auto-passes on dealbreakers,
so you don't spend time on matches you wouldn't date anyway.

## Notifications

With **Notify me for important events** on, you get a phone notification (tap to open the
dev panel) for the things that need your eyes — a reply waiting for your approval (explicit
handoff, a date/off-app move, or supervised mode) and safety flags (possible under-18
match). Routine auto-replies don't notify. Needs notification permission (requested on first
launch, Android 13+).

## Running it in the background (and the honest limit)

- It's **event- and timer-driven, not busy-looping** — it idles cheaply and does real work
  only when a dating app is in the foreground, so it won't peg the CPU or drain the battery.
  The overlay runs as a foreground service so Android keeps it alive.
- **Hard Android limit:** an accessibility service can only read and tap the app that's
  currently on screen. It **cannot** swipe/reply with the screen off or while you're in
  another app — no app can. Realistic use is "open the dating app, set the phone down, let
  it run," not "runs invisibly while you use your phone for other things."
