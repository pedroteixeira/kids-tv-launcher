package app.tvdascriancas

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusRestorer
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

private val Bg = Color(0xFF14112B)
private val Accent = Color(0xFFFFD54F)
private val Muted = Color(0xFFB8B2D9)

/** The kids' screen: posters of the approved titles; opening one starts a watchdog session. */
class MainActivity : ComponentActivity() {
    private lateinit var catalog: Catalog
    private var resumed by mutableStateOf(0) // bumps on every return, to re-read kids mode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = Catalog.load(this)
        setContent { KidsScreen(resumed) }
    }

    override fun onResume() {
        super.onResume()
        Kids.session = null
        resumed++
    }

    private fun watchdogOn(): Boolean {
        val me = ComponentName(this, Watchdog::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun installed(pkg: String) =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    @Composable
    private fun KidsScreen(@Suppress("UNUSED_PARAMETER") resumed: Int) {
        var kidsMode by remember { mutableStateOf(Kids.isKidsMode(this)) }
        var askPin by remember { mutableStateOf(false) }
        kidsMode = Kids.isKidsMode(this)
        val freeApps = catalog.freeApps.filter { installed(it.pkg) }

        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TV das crianças", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (!watchdogOn()) Text("vigia desligado  ", color = Color(0xFFFF8A80), fontSize = 14.sp)
                if (kidsMode) {
                    Text("modo crianças  ", color = Accent, fontSize = 14.sp)
                    SmallButton("Sair") { askPin = true }
                } else {
                    Text("TV livre  ", color = Muted, fontSize = 14.sp)
                    SmallButton("Ligar modo crianças") {
                        // Kids mode means nothing without the watchdog running: refuse it.
                        if (watchdogOn()) {
                            Kids.setKidsMode(this@MainActivity, true)
                            kidsMode = true
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Vigia desligado — reinstale pelo notebook (./install.sh)",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            Spacer(Modifier.size(20.dp))

            if (askPin) {
                PinPad(onDone = { ok ->
                    askPin = false
                    if (ok) {
                        Kids.setKidsMode(this@MainActivity, false)
                        kidsMode = false
                        // Hand the TV back: the normal Google TV home.
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
                return@Column
            }

            val first = remember { FocusRequester() }
            // Titles whose streaming app isn't on this TV are left out (each TV may differ).
            fun cardsOf(items: List<Item>) = items.filter { installed(it.app.pkg) }.map {
                Card(it.title, it.poster, it.app.label) to {
                    if (!Kids.open(this@MainActivity, it)) {
                        Toast.makeText(this@MainActivity, "${it.app.label} não abriu", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // One grid, a header row per section: Séries, Filmes, then the free apps.
            // "Continuar assistindo" (last opened here) first, then the rows of
            // titulos.json in its order, then the free apps.
            val byId = catalog.items.associateBy { it.id }
            val recent = Kids.recent(this@MainActivity).mapNotNull(byId::get)
            val sections: List<Pair<String, List<Pair<Card, () -> Unit>>>> = (listOf(
                "Continuar assistindo" to cardsOf(recent),
            ) + catalog.rows.map { row -> row to cardsOf(catalog.items.filter { it.row == row }) } + listOf(
                "Apps" to freeApps.map { app ->
                    Card(app.title, app.poster, "") to {
                        packageManager.getLeanbackLaunchIntentForPackage(app.pkg)
                            ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        Unit
                    }
                },
            )).filter { it.second.isNotEmpty() } // empty rows (nothing recent yet…) are left out
            // One horizontal row per section (the TV convention): ← → within a row,
            // ↑ ↓ between rows — a wrapped grid made the D-pad skip titles.
            // ↑ ↓ are ours: exactly one row per press, back to the title last focused in
            // that row. Compose's own 2D search across nested scrolling rows skipped rows.
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val rowFocus = remember(sections.size) { List(sections.size) { FocusRequester() } }
            var currentRow by remember { mutableStateOf(0) }
            fun goToRow(r: Int) {
                scope.launch {
                    listState.animateScrollToItem(r)
                    runCatching { rowFocus[r].requestFocus() }
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> {
                            if (currentRow < sections.lastIndex) goToRow(currentRow + 1)
                            true // never past the last row
                        }
                        Key.DirectionUp -> if (currentRow > 0) {
                            goToRow(currentRow - 1)
                            true
                        } else {
                            false // from the top row, up reaches the header buttons
                        }
                        else -> false
                    }
                },
            ) {
                sections.forEachIndexed { si, (name, cards) ->
                    item(key = name) {
                        Column {
                            Text(name, color = Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            LazyRow(
                                // ← → stay inside the row: at its ends focus stops instead of
                                // jumping to whatever lies that way (e.g. the "Sair" button).
                                // Entering the row restores the title last focused in it.
                                modifier = Modifier
                                    .focusRequester(rowFocus[si])
                                    .focusRestorer()
                                    .focusProperties {
                                        onExit = {
                                            if (requestedFocusDirection == FocusDirection.Left ||
                                                requestedFocusDirection == FocusDirection.Right
                                            ) cancelFocusChange()
                                        }
                                    }
                                    .onFocusChanged { if (it.hasFocus) currentRow = si }
                                    .focusGroup(),
                                // Room for the focused card's zoom on every side.
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                itemsIndexed(cards) { i, (card, open) ->
                                    Poster(
                                        card,
                                        Modifier.width(150.dp).then(if (si == 0 && i == 0) Modifier.focusRequester(first) else Modifier),
                                        open,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            LaunchedEffect(sections.size) { if (sections.isNotEmpty()) runCatching { first.requestFocus() } }
        }
    }

    private data class Card(val title: String, val poster: String?, val badge: String)

    @Composable
    private fun Poster(card: Card, modifier: Modifier, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Column(
            modifier
                .scale(if (focused) 1.08f else 1f)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2A2550))
                    .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (card.poster != null) {
                    AsyncImage(card.poster, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(card.title, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                }
            }
            Text(
                card.title, color = if (focused) Color.White else Muted, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
            if (card.badge.isNotEmpty()) Text(card.badge, color = Muted, fontSize = 11.sp)
        }
    }

    @Composable
    private fun SmallButton(label: String, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Text(
            label, color = if (focused) Bg else Muted, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (focused) Color.White else Color(0xFF2A2550))
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }

    /**
     * PIN entry the way Google TV asks for its own PIN: the digits come in rows of three
     * (1 2 3 / 4 5 6 / 7 8 9 / 0); ↑ ↓ pick the row, ← OK → type its 1st / 2nd / 3rd digit
     * (on the "0" row, any of them types 0). Typed digits show only as dots, and the D-pad
     * picture changes with the row, so a child watching sees no number being entered.
     * A number key types directly; Back deletes, or cancels when empty. A wrong PIN clears.
     */
    @Composable
    private fun PinPad(onDone: (Boolean) -> Unit) {
        val pin = BuildConfig.KIDS_PIN
        val rows = listOf("123", "456", "789", "0")
        var typed by remember { mutableStateOf("") }
        var row by remember { mutableStateOf(0) }
        var wrong by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }

        fun type(d: Char) {
            wrong = false
            typed += d
            if (typed.length == pin.length) {
                if (typed == pin) onDone(true) else { wrong = true; typed = ""; row = 0 }
            }
        }
        fun pick(col: Int) = rows[row].let { r -> type(if (r.length == 1) r[0] else r[col]) }

        Column(
            Modifier.fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = e.nativeKeyEvent.keyCode
                    when {
                        e.key == Key.DirectionUp -> row = (row + rows.size - 1) % rows.size
                        e.key == Key.DirectionDown -> row = (row + 1) % rows.size
                        e.key == Key.DirectionLeft -> pick(0)
                        e.key == Key.DirectionCenter || e.key == Key.Enter -> pick(1)
                        e.key == Key.DirectionRight -> pick(2)
                        e.key == Key.Back -> if (typed.isEmpty()) onDone(false) else typed = typed.dropLast(1)
                        code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> type('0' + (code - KeyEvent.KEYCODE_0))
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PIN para sair do modo crianças", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.size(18.dp))
            // Typed digits as dots only.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(pin.length) { i ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (i < typed.length) "●" else " ", color = Color.White, fontSize = 26.sp)
                        Box(Modifier.size(width = 48.dp, height = 2.dp).background(Muted))
                    }
                }
            }
            Spacer(Modifier.size(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                // The D-pad, labelled with the current row's digits.
                val r = rows[row]
                Box(
                    Modifier.size(170.dp).clip(RoundedCornerShape(85.dp)).background(Color(0xFFECEAF4)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▲", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
                    Text("▼", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                    if (r.length == 3) {
                        Text(r[0].toString(), color = Bg, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp))
                        Text(r[2].toString(), color = Bg, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp))
                    }
                    Text(if (r.length == 3) r[1].toString() else r, color = Bg, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                // All rows, the current one outlined.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEachIndexed { i, digits ->
                        Text(
                            digits.toList().joinToString("  "),
                            color = if (i == row) Color.White else Muted, fontSize = 18.sp,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                .border(if (i == row) 1.dp else 0.dp, if (i == row) Color.White else Color.Transparent, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(18.dp))
            Text(
                if (wrong) "PIN errado — tente de novo"
                else "↑ ↓ escolhe a linha · ← OK → digita · Voltar apaga",
                color = if (wrong) Color(0xFFFF8A80) else Muted, fontSize = 14.sp,
            )
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

private val StreamApp.label
    get() = when (this) {
        StreamApp.DISNEY -> "Disney+"
        StreamApp.APPLE -> "Apple TV"
        StreamApp.NETFLIX -> "Netflix"
        StreamApp.PRIME -> "Prime Video"
    }
