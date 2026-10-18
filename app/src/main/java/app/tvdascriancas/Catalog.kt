package app.tvdascriancas

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** A streaming app the TV deep-links into, and what the watchdog may read from it. */
enum class StreamApp(val key: String, val pkg: String) {
    DISNEY("disney", "com.disney.disneyplus"),
    APPLE("apple", "com.apple.atve.androidtv.appletv"),
    NETFLIX("netflix", "com.netflix.ninja"),
    PRIME("prime", "com.amazon.amazonvideo.livingroom");

    companion object {
        fun byKey(key: String) = entries.first { it.key == key }
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

class Catalog(val rows: List<String>, val items: List<Item>, val freeApps: List<FreeApp>) {
    companion object {
        /** assets/titles.json — the approved titles (see README). */
        fun load(context: Context): Catalog {
            val json = context.assets.open("titles.json").bufferedReader().use { JSONObject(it.readText()) }
            val items = json.getJSONArray("items").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val names = o.getJSONArray("names").let { n -> (0 until n.length()).map(n::getString) }
                    Item(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        row = o.optString("row").ifEmpty { if (o.getString("kind") == "movie") "Movies" else "Series" },
                        app = StreamApp.byKey(o.getString("app")),
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
}

/** Lowercase, accents stripped, whitespace collapsed — for matching names in screen text. */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
