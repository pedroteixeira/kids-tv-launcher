package app.tvdascriancas

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/** A streaming app the TV deep-links into, and what the watchdog may read from it. */
enum class StreamApp(val key: String, val pkg: String) {
    DISNEY("disney", "com.disney.disneyplus"),
    APPLE("apple", "com.apple.atve.androidtv.appletv"),
    NETFLIX("netflix", "com.netflix.ninja"),
    PRIME("prime", "com.amazon.amazonvideo.livingroom");

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
        fun byPkg(pkg: String?) = entries.firstOrNull { it.pkg == pkg }
    }
}

/** An approved title: its poster, its deep link, and the names the watchdog accepts. */
data class Item(
    val id: String,
    val title: String,
    val row: String,
    val app: StreamApp,
    val link: String,
    val poster: String?,
    val names: List<String>,
)

/** An app the kids may open freely (Jellyfin: the server already restricts them). */
data class FreeApp(val title: String, val pkg: String, val poster: String?)

class Catalog(
    val rows: List<String>,
    val items: List<Item>,
    val freeApps: List<FreeApp>,
    /** Why the TV's own titles.json couldn't be used (the bundled example shows instead). */
    val error: String? = null,
) {
    companion object {
        /** Where the installer puts this TV's list (`install.sh --titles`). */
        const val FILE = "titles.json"

        @Volatile private var cached: Pair<Pair<Long, Long>, Catalog>? = null

        /**
         * The approved titles: the TV's own titles.json (pushed by the installer into the
         * app's files folder) or, without one, the example bundled in the APK. Re-read
         * whenever the file changes, so a new list takes effect without restarting.
         */
        fun current(context: Context): Catalog {
            val file = context.getExternalFilesDir(null)?.let { File(it, FILE) }
            // adb push keeps the computer's modification time, so the size counts too.
            val stamp = file?.takeIf { it.isFile }?.let { it.lastModified() to it.length() } ?: (0L to 0L)
            cached?.let { (s, c) -> if (s == stamp) return c }
            val catalog = if (stamp == 0L to 0L) bundled(context) else try {
                parse(file!!.readText())
            } catch (e: Exception) {
                Log.w("KidsTV", "titles.json unreadable, showing the example", e)
                bundled(context).copy(error = "titles.json: ${e.message}")
            }
            cached = stamp to catalog
            return catalog
        }

        private fun bundled(context: Context): Catalog =
            parse(context.assets.open(FILE).bufferedReader().use { it.readText() })

        fun parse(text: String): Catalog {
            val json = JSONObject(text)
            val items = json.getJSONArray("items").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val names = o.getJSONArray("names").let { n -> (0 until n.length()).map(n::getString) }
                    val app = o.getString("app")
                    Item(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        row = o.optString("row").ifEmpty { if (o.optString("kind") == "movie") "Movies" else "Series" },
                        app = StreamApp.byKey(app) ?: throw IllegalArgumentException(
                            "unknown app \"$app\" (use ${StreamApp.entries.joinToString { it.key }})"
                        ),
                        link = o.getString("link"),
                        poster = o.optString("poster").ifEmpty { null },
                        names = names,
                    )
                }
            }
            val apps = json.optJSONArray("free_apps")?.let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    FreeApp(o.getString("title"), o.getString("package"), o.optString("poster").ifEmpty { null })
                }
            } ?: emptyList()
            val rows = json.optJSONArray("rows")?.let { a -> (0 until a.length()).map(a::getString) }
                ?: items.map { it.row }.distinct()
            return Catalog(rows, items, apps)
        }
    }

    fun copy(error: String?) = Catalog(rows, items, freeApps, error)
}

/** Lowercase, accents stripped, whitespace collapsed — for matching names in screen text. */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
