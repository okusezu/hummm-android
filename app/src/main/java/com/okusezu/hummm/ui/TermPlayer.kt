package com.okusezu.hummm.ui

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.okusezu.hummm.data.Track
import com.okusezu.hummm.playback.CastStatus
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin

/*
 * The full-screen player, drawn the way the desktop app draws its main
 * screen: header with the braille wave, the now-playing block (title —
 * artist / album • time / wave bar / ↺ ⇄ « ▶ » ♡, with optional
 * full-resolution cover art), a panel underneath (queue preview, queue or lyrics) and a row
 * of [ cast ] [ art ] [ lyrics ] buttons where the desktop has its mode bar.
 * Characters, spacing and dim/bold/accent styling follow hummm/app.py.
 */

private const val WAVE = "▁▂▃▄▅▆▇█▇▆▅▄▃▂"
private const val ART_CELLS = 16 // cover width in character cells (desktop: 18)

/** Desktop app.frame: ticks 8 times a second while playing, holds when paused. */
@Composable
private fun rememberFrame(live: Boolean): Int {
    var held by remember { mutableIntStateOf(0) }
    if (!live) return held
    val t = rememberInfiniteTransition(label = "frame")
    val f by t.animateFloat(0f, 8000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "frame")
    val frame = f.toInt()
    SideEffect { held = frame }
    return frame
}

/** Port of desktop am_wave(): the logo as 16 braille cells. */
private fun amWave(frame: Int, live: Boolean): String {
    val dots = arrayOf(intArrayOf(0x01, 0x08), intArrayOf(0x02, 0x10), intArrayOf(0x04, 0x20), intArrayOf(0x40, 0x80))
    fun envelope(x: Int): Double {
        val lo = 4; val mid = 16; val hi = 28
        if (x < lo || x > hi) return 0.0
        val t = if (x < mid) (x - lo).toDouble() / (mid - lo) else (x - mid).toDouble() / (hi - mid)
        return sin(PI * t).coerceAtLeast(0.0).pow(0.7)
    }
    val cols = (0 until 32).map { x ->
        var e = envelope(x)
        if (live && e > 0) e *= 0.78 + 0.22 * sin(x * 1.7 - frame * 0.8) * sin(x * 0.45 + frame * 0.3)
        val half = if (e > 0) minOf(2, ceil(e * 2 - 0.35).toInt()) else 0
        if (half <= 0) setOf(1) else (2 - half until 2 + half).toSet()
    }
    return buildString {
        for (c in 0 until 32 step 2) {
            var bits = 0
            for (side in 0..1) for (row in cols[c + side]) bits = bits or dots[row][side]
            append((0x2800 + bits).toChar())
        }
    }
}

@Composable
fun PlayerSheet(vm: AppViewModel) {
    val live = vm.current != null && vm.isPlaying
    val frame = rememberFrame(live)
    if (vm.visualiser) return Visualiser(onClose = { vm.visualiser = false })
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HeaderBar(frame, live)
            HorizontalDivider()
            NowBlock(vm, frame)
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (vm.playerPage) {
                    PlayerPage.NOW_PLAYING -> QueuePreview(vm)
                    PlayerPage.QUEUE -> QueuePanel(vm)
                    PlayerPage.LYRICS -> LyricsPanel(vm)
                }
            }
            HorizontalDivider()
            ActionBar(vm)
        }
    }
}

/** The desktop header's wave, mirrored on the right so "hummm ♪" sits between two of them. */
@Composable
private fun HeaderBar(frame: Int, live: Boolean) {
    val wave = amWave(frame / 2, live)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(wave, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
        Text("hummm ♪", fontWeight = FontWeight.Bold)
        Text(mirrorBraille(wave), Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.End)
    }
}

/** Reverses a braille string left to right, swapping each cell's two dot columns. */
private fun mirrorBraille(text: String): String = text.reversed().map { ch ->
    val bits = ch.code - 0x2800
    val left = bits and 0x47 // dots 1, 2, 3, 7
    val right = bits and 0xB8 // dots 4, 5, 6, 8
    val swapped = ((left and 0x07) shl 3) or ((left and 0x40) shl 1) or ((right and 0x38) shr 3) or ((right and 0x80) shr 1)
    (0x2800 + swapped).toChar()
}.joinToString("")

@Composable
private fun NowBlock(vm: AppViewModel, frame: Int) {
    val art by vm.settings.art.collectAsState()
    val t = vm.current
    if (art && t != null) ArtLayout(vm, t, frame) else PlainLayout(vm, frame)
}

/** Desktop _art_layout(): cover on the left, the track's lines beside it. */
@Composable
private fun ArtLayout(vm: AppViewModel, t: Track, frame: Int) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)
            .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag > 40) vm.playerOpen = false } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(t.thumbnail, Modifier.width(cellWidth() * ART_CELLS))
        Spacer(Modifier.width(cellWidth() * 3))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, Modifier.clickable(enabled = t.artistIds.isNotEmpty()) { vm.goToArtist(t) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.album, Modifier.clickable(enabled = t.albumId.isNotEmpty()) { vm.goToAlbum(t) }, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeLine(vm, t), color = dim, maxLines = 1)
            CastingLine()
            Text("")
            WaveBar(vm, frame)
            Text("")
            Controls(vm, Modifier.fillMaxWidth(), spread = true)
        }
    }
}

/** "casting to Living Room TV · ♪ 45%" under the time while a Chromecast plays. */
@Composable
private fun CastingLine() {
    val device by CastStatus.device.collectAsState()
    val volume by CastStatus.volume.collectAsState()
    device?.let {
        Text(
            "casting to $it" + (volume?.let { v -> " · ♪ $v%" } ?: ""),
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun timeLine(vm: AppViewModel, t: Track) =
    "${fmtTime(vm.positionMs)} / ${fmtTime(vm.durationMs.takeIf { it > 0 } ?: t.duration * 1000L)}" +
        if (vm.buffering) "  buffering…" else ""

/** The cover at full resolution, square-cornered like everything else; a dim block until it loads. */
@Composable
private fun CoverArt(url: String, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNotEmpty()) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Desktop NowPlaying without cover art: every line centred. */
@Composable
private fun PlainLayout(vm: AppViewModel, frame: Int) {
    val t = vm.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)
            .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag > 40) vm.playerOpen = false } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Now Playing", fontWeight = FontWeight.Bold)
        Text("───────────", color = dim)
        if (t == null) {
            Text("")
            Text("nothing playing — search, or try a vibe", color = dim)
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.title) }
                    append(" — ")
                    append(t.artist)
                },
                Modifier.clickable(enabled = t.artistIds.isNotEmpty()) { vm.goToArtist(t) },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(t.album.takeIf { it.isNotEmpty() }, timeLine(vm, t)).joinToString(" • "),
                Modifier.clickable(enabled = t.albumId.isNotEmpty()) { vm.goToAlbum(t) },
                color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            CastingLine()
        }
        Text("")
        WaveBar(vm, frame)
        Text("")
        Controls(vm)
    }
}

private val dimStyle @Composable get() = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Width of one monospace character cell in the body style. */
@Composable
private fun cellWidth() = with(LocalDensity.current) {
    rememberTextMeasurer().measure("─", MaterialTheme.typography.bodyLarge).size.width.toDp()
}

/**
 * A panel heading as the desktop draws it: bold title, then a dim rule of the
 * same length. [actions] sit at the right as tappable words.
 */
@Composable
private fun PanelTitle(title: String, detail: String = "", onTitle: (() -> Unit)? = null, actions: List<Pair<String, () -> Unit>> = emptyList()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                if (detail.isNotEmpty()) withStyle(dimStyle) { append(" · $detail") }
            },
            Modifier.weight(1f).clickable(enabled = onTitle != null) { onTitle?.invoke() },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TermActions(actions)
    }
    Text("─".repeat(title.length), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Tappable words separated by dim dots, e.g. "clear · save". */
@Composable
private fun TermActions(actions: List<Pair<String, () -> Unit>>) {
    actions.forEachIndexed { i, (label, action) ->
        if (i > 0) Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.clickable(onClick = action).padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
    }
}

/** Desktop _bar(): played part as a travelling ▁▂▃ wave in the accent, ● head, ─ rest. */
@Composable
private fun WaveBar(vm: AppViewModel, frame: Int) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var dragging by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { vm.seekFraction((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(vm::seekFraction); dragging = null },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // The desktop bar is the panel width less 10 cells.
        val width = ((maxWidth / cellWidth()).toInt() - 4).coerceAtLeast(10)
        val frac = dragging ?: if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f
        val filled = ((width - 1) * frac).toInt()
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = accent)) { for (i in 0 until filled) append(WAVE[(i + frame) % WAVE.length]) }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(if (vm.current != null) "●" else "─") }
                withStyle(SpanStyle(color = dim)) { append("─".repeat(width - filled - 1)) }
            },
            maxLines = 1, softWrap = false,
        )
    }
}

/** Desktop _controls() plus prev/next: ↺ (↺¹)  ⇄  «  ▶/‖  »  ♡/♥, each tappable (‖ for ⏸ and « » for ⏮ ⏭, which phones draw as emoji). */
@Composable
private fun Controls(vm: AppViewModel, modifier: Modifier = Modifier, spread: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val shuffle by vm.shuffle.collectAsState()
    val t = vm.current
    val liked = t != null && t.videoId in vm.likedIds
    val gap = cellWidth() * 2
    // spread: fill a narrow column (beside the cover) instead of fixed two-cell gaps.
    Row(modifier, horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        val repeatOn = vm.repeatMode != Player.REPEAT_MODE_OFF
        Glyph(if (vm.repeatMode == Player.REPEAT_MODE_ONE) "↺¹" else "↺", if (repeatOn) accent else dim, repeatOn, vm::cycleRepeat)
        Glyph("⇄", if (shuffle) accent else dim, shuffle, vm::toggleShuffle)
        Glyph("«", MaterialTheme.colorScheme.onSurface, true, vm::previous)
        Glyph(if (vm.isPlaying) "‖" else "▶︎", MaterialTheme.colorScheme.onSurface, true, vm::togglePlay)
        Glyph("»", MaterialTheme.colorScheme.onSurface, true, vm::next)
        Glyph(if (liked) "♥" else "♡", if (liked) Color(0xFFE5484D) else dim, liked) { vm.toggleLike() }
    }
}

@Composable
private fun Glyph(text: String, color: Color, bold: Boolean, onClick: () -> Unit) {
    // Phone-sized hit area around a glyph drawn a little larger than body text.
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 22.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

/** Desktop QueuePreview: "Queue", a rule, then "  1. title  artist" lines. */
@Composable
private fun QueuePreview(vm: AppViewModel) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val auto by vm.settings.autoDiscover.collectAsState()
    val metric by vm.settings.metric.collectAsState()
    val start = vm.queueIndex + 1
    val upcoming = if (start < vm.queue.size) vm.queue.subList(start, vm.queue.size) else emptyList()
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        PanelTitle("Queue", onTitle = { vm.playerPage = PlayerPage.QUEUE })
        if (upcoming.isEmpty()) {
            Text("  Queue is empty", color = dim)
            if (auto) Text("  auto-discover on ($metric)", color = dim)
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val rowHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() } + 6.dp
            val room = (maxHeight / rowHeight).toInt().coerceAtLeast(1)
            val shown = if (upcoming.size > room) room - 1 else room
            Column {
                upcoming.take(shown).forEachIndexed { i, t ->
                    Text(
                        buildAnnotatedString {
                            withStyle(dimStyle) { append("  %2d. ".format(i + 1)) }
                            append(t.title)
                            withStyle(dimStyle) { append("  ${t.artist}") }
                        },
                        Modifier.fillMaxWidth().height(rowHeight).clickable { vm.jumpTo(start + i) }.padding(vertical = 3.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (upcoming.size > shown) Text("  … ${upcoming.size - shown} more", color = dim)
            }
        }
    }
}

/**
 * Desktop QueueList: "▶ " marks the playing song, played songs are dim, each
 * line ends with artist and length. Tap plays (Enter); long-press moves the
 * cursor onto a row so the mode bar's remove / move / add actions apply to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuePanel(vm: AppViewModel) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem((vm.queueIndex - 3).coerceAtLeast(0)) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    fun needsAccount(block: () -> Unit) = { if (vm.music.authed) block() else vm.say("That needs sign-in. Open Library → Settings") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PanelTitle(
            "Queue", if (vm.queue.isEmpty()) "" else "${vm.queue.size} songs",
            actions = listOf("clear" to vm::clearUpcoming, "save" to needsAccount { saving = true }, "close" to { vm.playerPage = PlayerPage.NOW_PLAYING }),
        )
        if (vm.queue.isEmpty()) {
            Text("Queue is empty. Search, browse the library or set a vibe.", color = dim)
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(vm.queue, key = { i, t -> "$i/${t.videoId}" }) { i, t ->
                val current = i == vm.queueIndex
                val title = when {
                    current -> SpanStyle(color = accent, fontWeight = FontWeight.Bold)
                    i < vm.queueIndex -> SpanStyle(color = dim)
                    else -> SpanStyle()
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(if (current) "▶︎ " else "  ") }
                        withStyle(title) { append(t.title) }
                        withStyle(SpanStyle(color = dim)) {
                            append("  ${t.artist}")
                            if (t.duration > 0) append("  ${fmtTime(t.duration * 1000L)}")
                        }
                    },
                    Modifier.fillMaxWidth()
                        .background(if (i == vm.queueCursor) MaterialTheme.colorScheme.outline else Color.Transparent)
                        .combinedClickable(
                            onClick = { vm.queueCursor = null; vm.jumpTo(i) },
                            onLongClick = { vm.queueCursor = if (vm.queueCursor == i) null else i },
                        )
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The desktop's d / K / J / p keys, for the long-pressed row.
        val cursor = vm.queueCursor?.takeIf { it in vm.queue.indices }
        if (cursor != null) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TermActions(listOf(
                    "remove" to { vm.removeAt(cursor); vm.queueCursor = cursor.coerceAtMost(vm.queue.size - 2).takeIf { it >= 0 } },
                    "up" to { if (cursor > 0) { vm.move(cursor, cursor - 1); vm.queueCursor = cursor - 1 } },
                    "down" to { if (cursor < vm.queue.lastIndex) { vm.move(cursor, cursor + 1); vm.queueCursor = cursor + 1 } },
                    "add to playlist" to needsAccount { adding = listOf(vm.queue[cursor]) },
                    "done" to { vm.queueCursor = null },
                ))
            }
        } else {
            Text("tap to play · hold a song to edit it", Modifier.padding(top = 8.dp, bottom = 2.dp), color = dim, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (saving) SaveQueueDialog(onSave = { vm.savePlaylist(it, vm.queue) }, onDismiss = { saving = false })
    adding?.let { PlaylistPicker(vm, it, onDismiss = { adding = null }) }
}

/**
 * Desktop LyricsView: the current line bold in the accent and kept centred,
 * the rest dim (plain for untimed lyrics), source in dim italics at the end.
 * Touching the list stops following; tapping a timed line seeks to it.
 */
@Composable
private fun LyricsPanel(vm: AppViewModel) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val t = vm.current
    LaunchedEffect(t?.videoId) { vm.loadLyrics(); vm.lyricsFollow = true }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        PanelTitle(
            "Lyrics", t?.let { "${it.title} — ${it.artist}" }.orEmpty(),
            actions = if (vm.lyricsFollow) emptyList() else listOf("follow" to { vm.lyricsFollow = true }),
        )
        val state = vm.lyrics
        val lyr = (state as? Load.Ok)?.value
        val message = when {
            t == null -> "nothing playing"
            state is Load.Err -> state.message
            state !is Load.Ok -> "loading lyrics…"
            lyr == null || lyr.lines.isEmpty() -> "no lyrics available for this song"
            else -> null
        }
        if (message != null || lyr == null) {
            Text(message.orEmpty(), color = dim)
            return@Column
        }
        val cur = if (lyr.timed) lyr.lines.indexOfLast { (it.startMs ?: 0) <= vm.positionMs }.coerceAtLeast(0) else -1
        val listState = rememberLazyListState()
        LaunchedEffect(cur, vm.lyricsFollow) {
            if (vm.lyricsFollow && cur >= 0) listState.animateScrollToItem(cur, -listState.layoutInfo.viewportSize.height / 2)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); vm.lyricsFollow = false }
            },
        ) {
            itemsIndexed(lyr.lines) { i, line ->
                Text(
                    line.text.ifBlank { " " },
                    Modifier.fillMaxWidth()
                        .clickable(enabled = lyr.timed) { line.startMs?.let { vm.seekTo(it) }; vm.lyricsFollow = true }
                        .padding(vertical = 2.dp),
                    color = when {
                        i == cur -> accent
                        lyr.timed -> dim
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (i == cur) FontWeight.Bold else null,
                )
            }
            if (lyr.source.isNotEmpty()) item {
                Text(lyr.source, Modifier.padding(top = 8.dp, bottom = 16.dp), color = dim, fontStyle = FontStyle.Italic)
            }
        }
    }
}

/**
 * The desktop's mode bar as a grid of buttons:
 * radio · queue · lyrics · visual / art · cast · eq (prev/next live in Controls).
 * The open panel, the art toggle and an active Cast session show in reverse video.
 */
@Composable
private fun ActionBar(vm: AppViewModel) {
    val art by vm.settings.art.collectAsState()
    val casting by CastStatus.device.collectAsState()
    var picking by remember { mutableStateOf(false) }
    fun toggle(page: PlayerPage) { vm.playerPage = if (vm.playerPage == page) PlayerPage.NOW_PLAYING else page }
    val rows = listOf(
        listOf(
            Triple("radio", false, { vm.radio() }),
            Triple("queue", vm.playerPage == PlayerPage.QUEUE, { toggle(PlayerPage.QUEUE) }),
            Triple("lyrics", vm.playerPage == PlayerPage.LYRICS, { toggle(PlayerPage.LYRICS) }),
            Triple("visual", false, { vm.visualiser = true }),
        ),
        listOf(
            Triple("art", art, { vm.settings.setArt(!art) }),
            Triple("cast", casting != null, { picking = true }),
            Triple("eq", false, { vm.playerOpen = false; vm.push(Route.Equalizer) }),
        ),
    )
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, on, action) -> TermButton(label, on, action, Modifier.weight(1f)) }
            }
        }
    }
    if (picking) CastPicker(onDismiss = { picking = false })
}

/** A bordered terminal button; reverse video while [on]. */
@Composable
private fun TermButton(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .border(1.dp, if (on) fg else MaterialTheme.colorScheme.outline)
            .background(if (on) fg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) MaterialTheme.colorScheme.background else fg, maxLines = 1, fontWeight = if (on) FontWeight.Bold else null)
    }
}
