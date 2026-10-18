package app.tvdascriancas

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Keeps the kids inside the approved titles. In kids mode, whatever app is in
 * front must be ours, a free app (Jellyfin), a harmless system piece, or the streaming
 * app of the title just opened from our screen — and that app must stay on the title.
 * Anything else sends the TV back to the kids' screen.
 *
 * What each app lets us see (tested on the TCL, 2026-10-17):
 * - Disney+: "now playing" names the title; full screen text (menus detectable).
 * - Apple TV: playing/paused only; full screen text (show page names the show).
 * - Netflix: playing/paused/stopped only; no screen text at all — so the rule is
 *   "must be playing", and leaving the player (stopped) ends the session.
 */
class Watchdog : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var catalog: Catalog

    // A bad condition must persist for its grace period before we act, so loading
    // screens and transitions between episodes don't bounce the kids.
    private var badSince = 0L
    private var lastReason = ""
    private var lastLogged = "" // last verdict logged (only changes are logged)
    // After sending the TV home, the old app stays "in front" for a moment; don't
    // keep kicking it while our screen comes up.
    private var cooldownUntil = 0L

    // Per-session memory (reset when a new session starts).
    private var sessionKey = 0L
    private var everPlaying = false
    private var backSinceText = false // Back pressed since the last readable screen
    private var everOk = false // the title has been seen on screen / playing this session
    private var pausedSince = 0L

    // Idea, not built here: this ticker already knows, once a second, which title is in
    // front and whether it is playing — enough to log the kids' TV time (one segment per
    // title, counting only seconds that play), keep it in a file on the TV and send it to
    // a server you run, then chart minutes per day. The original project does exactly
    // that; see "Ideias" in the README.
    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        catalog = Catalog.load(this)
        // The system can rebind the service (e.g. while a UI dump runs): never stack a
        // second ticker on top of the first.
        handler.removeCallbacks(tick)
        handler.post(tick)
        Log.i(TAG, "vigia ligado — ${catalog.items.size} títulos")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = evaluate()

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private sealed interface Verdict {
        data object Ok : Verdict
        data object Unknown : Verdict // e.g. a blank loading screen: keep waiting
        data class Bad(val reason: String, val graceMs: Long) : Verdict
    }

    private fun evaluate() {
        if (!::catalog.isInitialized) return
        if (!Kids.isKidsMode(this)) return clear()
        if (SystemClock.elapsedRealtime() < cooldownUntil) return
        val pkg = foregroundPackage() ?: return
        val verdict = when {
            pkg == packageName -> Verdict.Ok
            pkg in SYSTEM_OK || catalog.freeApps.any { it.pkg == pkg } -> Verdict.Ok
            else -> sessionVerdict(pkg)
        }
        val label = "$pkg: $verdict"
        if (label != lastLogged) {
            Log.d(TAG, "estado — $label")
            lastLogged = label
        }
        when (verdict) {
            Verdict.Ok -> clear()
            Verdict.Unknown -> {}
            is Verdict.Bad -> {
                val now = SystemClock.elapsedRealtime()
                if (badSince == 0L || verdict.reason != lastReason) {
                    badSince = now
                    lastReason = verdict.reason
                }
                if (now - badSince >= verdict.graceMs) kick(pkg, verdict.reason)
            }
        }
    }

    private fun sessionVerdict(pkg: String): Verdict {
        val s = Kids.session
        if (s == null || s.item.app.pkg != pkg) return Verdict.Bad("app fora da lista: $pkg", 0)
        if (s.startedAt != sessionKey) {
            sessionKey = s.startedAt
            everPlaying = false
            everOk = false
            backSinceText = false
            pausedSince = 0
        }
        val verdict = when (s.item.app) {
            StreamApp.DISNEY -> disney(s.item, pkg)
            StreamApp.APPLE -> apple(s.item, pkg)
            StreamApp.NETFLIX, StreamApp.PRIME -> playbackOnly(s, pkg)
        }
        if (verdict == Verdict.Ok) everOk = true
        // Opening a title goes through the app's own splash, profile picker and loading
        // screens (Apple TV's cold start takes well over 8 s, text-less). Until the title
        // shows up once — or the start window runs out — just wait.
        // (Netflix/Prime have their own, shorter start rule: must start playing in 15 s.)
        // Back ends the start window: the kids are leaving, not waiting for a load.
        val starting = s.item.app in SCREEN_READ_APPS && !everOk && !backSinceText &&
            SystemClock.elapsedRealtime() - s.startedAt < START_WINDOW_MS
        return when {
            verdict is Verdict.Bad && starting -> Verdict.Unknown
            // "Can't tell yet" (a profile picker, a title that hasn't started) is only
            // tolerated inside the start window — never a place to stay.
            verdict == Verdict.Unknown && !starting && s.item.app in SCREEN_READ_APPS ->
                Verdict.Bad("sem confirmar o título", BLIND_GRACE_MS)
            else -> verdict
        }
    }

    private fun disney(item: Item, pkg: String): Verdict {
        val texts = screenText()
        if (texts.any { t -> DISNEY_CATALOG.any { t.contains(it) } }) return Verdict.Bad("catálogo do Disney+", 1500)
        if (texts.any { t -> PROFILE_PICKER.any { t.contains(it) } }) return Verdict.Unknown
        val media = media(pkg)
        val title = media?.metadata?.description?.title?.toString()
        if (title != null && media.isPlayingOrPaused()) {
            return if (item.matches(title)) Verdict.Ok else Verdict.Bad("tocando outro título: $title", 1500)
        }
        return screenVerdict(item, texts, pkg)
    }

    private fun apple(item: Item, pkg: String): Verdict {
        val texts = screenText()
        if (texts.any { t -> APPLE_CATALOG.any { t.contains(it) } }) return Verdict.Bad("catálogo da Apple TV", 1500)
        if (texts.any { t -> PROFILE_PICKER.any { t.contains(it) } }) return Verdict.Unknown
        return screenVerdict(item, texts, pkg)
    }

    /** The screen names the title → fine; a populated screen that doesn't → wandered off. */
    private fun screenVerdict(item: Item, texts: List<String>, pkg: String): Verdict = when {
        texts.isEmpty() -> blindVerdict(pkg)
        // Only the top of the screen counts — the page's own title / metadata line comes
        // first in the tree (Disney+: the title node; Apple TV: "Show, Genre, …"), while a
        // recommendation rail further down may list an approved title on another page.
        texts.take(HEADER_NODES).any { item.matches(it) } -> Verdict.Ok
        else -> Verdict.Bad("tela fora de ${item.title}", 4000)
    }

    /**
     * A screen with no readable text. On Apple TV that is both the video player and the
     * app's own browse screens (drawn without accessibility text), with the same playback
     * state — paused, position kept. What tells them apart is how the kids got there: the
     * player is entered with OK/Play from a readable screen, browsing with Back from it.
     * So: Back since the last readable screen → wandered off; otherwise treat it as the
     * player (playing ok, paused ok for a while).
     */
    private fun blindVerdict(pkg: String): Verdict {
        if (backSinceText) return Verdict.Bad("voltou da página do título", 2000)
        val ps = media(pkg)?.playbackState
        return when (ps?.state) {
            // Playing is the best evidence a text-less screen gives (Apple TV's show page
            // after the profile picker exposes no text at all) — it also verifies the title.
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> {
                everPlaying = true
                pausedSince = 0
                Verdict.Ok
            }
            // A pause only counts once something played or the title was seen; before that
            // a paused session is stale (another title, a preview) — the start window decides.
            PlaybackState.STATE_PAUSED -> if (everPlaying || everOk) {
                val now = SystemClock.elapsedRealtime()
                if (pausedSince == 0L) pausedSince = now
                if (now - pausedSince > MAX_PAUSE_MS) Verdict.Bad("pausado há muito tempo", 0) else Verdict.Ok
            } else {
                Verdict.Unknown
            }
            else -> if (everPlaying || everOk) Verdict.Bad("tela sem informação e nada tocando", BLIND_GRACE_MS)
            else Verdict.Unknown
        }
    }

    /** Observe (never consume) the remote: only Back matters, see [blindVerdict]. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            backSinceText = true
            Log.d(TAG, "tecla Voltar")
        }
        return false
    }

    private fun notStarted(s: Kids.Session, now: Long): Verdict =
        if (now - s.startedAt < START_TIMEOUT_MS) Verdict.Unknown else Verdict.Bad("não começou a tocar", 0)

    /** Netflix (and Prime): nothing to read but the playback state. */
    private fun playbackOnly(s: Kids.Session, pkg: String): Verdict {
        val now = SystemClock.elapsedRealtime()
        val state = media(pkg)?.playbackState?.state
        return when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> {
                everPlaying = true
                pausedSince = 0
                Verdict.Ok
            }
            // A pause only counts once the title has actually played: paused before that
            // is a stale session or a browse/profile screen, not our title.
            PlaybackState.STATE_PAUSED -> if (everPlaying) {
                if (pausedSince == 0L) pausedSince = now
                if (now - pausedSince > MAX_PAUSE_MS) Verdict.Bad("pausado há muito tempo", 0) else Verdict.Ok
            } else {
                notStarted(s, now)
            }
            else -> if (!everPlaying) notStarted(s, now) else Verdict.Bad("saiu do player", 5000)
        }
    }

    private fun kick(pkg: String, reason: String) {
        Log.i(TAG, "voltando para a TV das crianças: $reason ($pkg)")
        media(pkg)?.transportControls?.pause()
        clear()
        cooldownUntil = SystemClock.elapsedRealtime() + KICK_COOLDOWN_MS
        Kids.home(this)
    }

    private fun clear() {
        badSince = 0
        lastReason = ""
    }

    /** The package of the focused application window (system overlays don't count). */
    private fun foregroundPackage(): String? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive }
        return (w?.root ?: rootInActiveWindow)?.packageName?.toString()
    }

    /** Every text / content description on screen, folded; bounded walk. */
    private fun screenText(): List<String> = readScreen().also { if (it.isNotEmpty()) backSinceText = false }

    private fun readScreen(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<String>()
        // Depth-first, in child order: document order, so the page's header comes first
        // (screenVerdict only trusts the first HEADER_NODES texts).
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (stack.isNotEmpty() && seen < 800) {
            val n = stack.removeLast()
            seen++
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += fold(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += fold(it) }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
        }
        return out
    }

    private fun media(pkg: String): MediaController? = try {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, MediaListener::class.java))
            .firstOrNull { it.packageName == pkg }
    } catch (e: SecurityException) {
        null // notification listener not granted: playback state unavailable
    }

    private fun MediaController.isPlayingOrPaused() = playbackState?.state in setOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PAUSED,
    )

    /**
     * A text names this title when it IS one of its names, or starts with one followed by
     * a separator — Apple TV's show line reads "The Snoopy Show, Animation, Comedy…".
     * Never plain containment: "Bluey Compilations" is another title, not Bluey.
     */
    private fun Item.matches(text: String): Boolean {
        val t = fold(text)
        return names.any { name ->
            val n = fold(name)
            t == n || (t.startsWith(n) && t[n.length] in TITLE_SEPARATORS)
        }
    }

    companion object {
        const val TAG = "KidsTV"
        private const val MAX_PAUSE_MS = 3 * 60_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val KICK_COOLDOWN_MS = 2_500L
        private const val BLIND_GRACE_MS = 8_000L
        private const val START_WINDOW_MS = 30_000L
        private const val HEADER_NODES = 12
        private val SCREEN_READ_APPS = setOf(StreamApp.DISNEY, StreamApp.APPLE)
        private val TITLE_SEPARATORS = setOf(',', ':', ';', '(', '-', '–', '—', '|', '·')

        // System pieces that may come to the front without being "an app": the
        // keyboard, permission prompts, the voice assistant overlay (anything it
        // opens is judged on its own).
        private val SYSTEM_OK = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.inputmethod.latin",
            "com.google.android.tvkeyboard",
            "com.google.android.katniss",
        )

        // Folded (lowercase, no accents) markers of each app's browse screens.
        private val DISNEY_CATALOG = listOf("items in the menu", "itens no menu", "profile management")
        private val APPLE_CATALOG = listOf("barra lateral", "mudar de perfil", "sidebar", "switch profile")
        private val PROFILE_PICKER = listOf("quem esta assistindo", "who's watching", "whos watching")
    }
}
