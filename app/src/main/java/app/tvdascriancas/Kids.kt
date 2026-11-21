package app.tvdascriancas

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Shared state between the launcher screen and the watchdog (same process).
 *
 * - kids mode: the watchdog only acts while it's on. Off by default, so the TV works
 *   normally for the adults; switched on from our screen when the TV goes to the
 *   kids, switched off only with the PIN. Persisted (survives reboots).
 * - session: the title the kids just opened from our screen. The watchdog lets its
 *   app run only while the screen stays on that title.
 */
object Kids {
    data class Session(val item: Item, val startedAt: Long = SystemClock.elapsedRealtime())

    @Volatile var session: Session? = null

    private const val PREFS = "kids_tv"
    private const val KIDS_MODE = "kids_mode"
    private const val RECENT = "recent" // item ids, most recent first
    private const val RECENT_MAX = 6
    private const val PIN = "pin"

    /** Where the installer drops a new PIN; [adoptPin] moves it into private storage. */
    const val PIN_FILE = "pin"

    /** The PIN that leaves kids mode: set by the installer, else the build's default. */
    fun pin(context: Context): String {
        adoptPin(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PIN, null)
            ?: BuildConfig.KIDS_PIN
    }

    /**
     * The installer can only write to the app's shared files folder, so a PIN it sends
     * arrives there as a file: keep it in the app's private preferences and delete the
     * file, so it doesn't stay where adb (or a file manager with full access) can read it.
     */
    fun adoptPin(context: Context) {
        val file = context.getExternalFilesDir(null)?.let { File(it, PIN_FILE) } ?: return
        if (!file.isFile) return
        val pin = try {
            file.readText().trim()
        } catch (e: Exception) {
            // Left in place: the installer's next run fixes it (it makes the file readable).
            Log.w("KidsTV", "PIN file unreadable", e)
            return
        }
        if (pin.matches(Regex("\\d{4,8}"))) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PIN, pin).commit()
        } else {
            Log.w("KidsTV", "PIN file ignored: not 4-8 digits")
        }
        file.delete()
    }

    /** The titles last opened from the kids' screen on this TV, most recent first. */
    fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECENT, "")!!
            .split('\n').filter { it.isNotEmpty() }

    private fun remember(context: Context, id: String) {
        val list = (listOf(id) + recent(context).filter { it != id }).take(RECENT_MAX)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECENT, list.joinToString("\n")).apply()
    }

    fun isKidsMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KIDS_MODE, false)

    fun setKidsMode(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KIDS_MODE, on).apply()
    }

    /**
     * Open [item] in its streaming app and start a session for the watchdog. False when
     * the app isn't there (not installed on this TV, or the link isn't handled).
     */
    fun open(context: Context, item: Item): Boolean {
        session = Session(item)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.link))
            .setPackage(item.app.pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (item.app == StreamApp.NETFLIX) intent.putExtra("source", "30") // what Netflix's own deep links carry
        return try {
            context.startActivity(intent)
            remember(context, item.id)
            true
        } catch (e: ActivityNotFoundException) {
            session = null
            false
        }
    }

    /** Bring the kids' screen back to the front (ends any session). */
    fun home(context: Context) {
        session = null
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
