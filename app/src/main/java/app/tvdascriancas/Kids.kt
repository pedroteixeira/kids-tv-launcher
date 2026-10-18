package app.tvdascriancas

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock

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
