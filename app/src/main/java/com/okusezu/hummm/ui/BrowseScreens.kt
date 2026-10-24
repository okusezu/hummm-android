package com.okusezu.hummm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okusezu.hummm.data.Entry
import com.okusezu.hummm.data.Item
import com.okusezu.hummm.data.Section
import com.okusezu.hummm.data.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HummmTopBar(vm: AppViewModel, title: String? = null, actions: @Composable () -> Unit = {}) {
    Column {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaveLogo(live = vm.isPlaying, width = 48.dp, height = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(title ?: "hummm", fontWeight = FontWeight.Bold)
                }
            },
            actions = { actions() },
        )
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Column {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { actions() },
        )
        HorizontalDivider()
    }
}

/** A titled shelf: songs as a list when it's a song section, otherwise cards. */
@Composable
fun Shelf(vm: AppViewModel, section: Section, asList: Boolean = false) {
    var menuFor by remember { mutableStateOf<Track?>(null) }
    val tracks = section.entries.filterIsInstance<Track>()
    SectionHeader(section.title)
    if (asList) {
        Column {
            section.entries.forEach { e -> EntryRow(vm, e, tracks) }
        }
    } else {
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(section.entries) { e ->
                when (e) {
                    is Track -> Card(e.title, e.artist, e.thumbnail,
                        onClick = { vm.playList(tracks, tracks.indexOf(e)) }, onLongClick = { menuFor = e })
                    is Item -> Card(e.title, e.subtitle, e.thumbnail, onClick = { vm.open(e) })
                }
            }
        }
    }
    menuFor?.let { TrackMenu(vm, listOf(it), it.title) { menuFor = null } }
}

@Composable
fun EntryRow(vm: AppViewModel, e: Entry, siblings: List<Track>) {
    when (e) {
        is Track -> TrackRow(vm, e, onClick = { vm.playList(siblings, siblings.indexOf(e).coerceAtLeast(0)) })
        is Item -> ItemRow(e) { vm.open(e) }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize()) {
        HummmTopBar(vm) {
            IconButton(onClick = { vm.loadFeed() }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = { vm.push(Route.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        LoadView(vm.feed, onRetry = vm::loadFeed) { sections ->
            LazyColumn(contentPadding = padding) {
                if (vm.account == null) item {
                    Text(
                        "Guest mode: search, radio, vibes and lyrics all work. Sign in for your library and likes.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(sections, key = { it.title + it.entries.size }) { Shelf(vm, it) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
fun SearchScreen(vm: AppViewModel, padding: PaddingValues) {
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
            placeholder = { Text("Songs, albums, artists, playlists") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
            singleLine = true,
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(); fm.clearFocus() }),
        )
        LaunchedEffect(Unit) { if (vm.results == null) focus.requestFocus() }
        LoadView(vm.results, onRetry = { vm.search() }) { sections ->
            if (sections.isEmpty()) Text("No results", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = padding) {
                for (s in sections) {
                    item(key = "h" + s.title) { SectionHeader(s.title) }
                    val tracks = s.entries.filterIsInstance<Track>()
                    items(s.entries, key = { s.title + (if (it is Track) it.videoId else (it as Item).id) }) { EntryRow(vm, it, tracks) }
                }
            }
        }
    }
}

/** Album, playlist or artist page. */
@Composable
fun DetailScreen(vm: AppViewModel, item: Item, padding: PaddingValues) {
    var tracks by remember(item.id) { mutableStateOf<Load<List<Track>>>(Load.Loading) }
    var sections by remember(item.id) { mutableStateOf<List<Section>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(item.id, attempt) {
        tracks = vm.cached("tracks:${item.id}") { vm.music.itemTracks(item) }
        if (item.kind == Item.Kind.ARTIST) {
            (vm.cached("artist:${item.id}") { vm.music.artistSections(item.id) } as? Load.Ok)?.let { r ->
                // Songs are listed above; show albums, singles, related artists etc. as shelves.
                sections = r.value.filter { s -> s.entries.any { it is Item } }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(item.title, onBack = { vm.back() }) {
            val list = (tracks as? Load.Ok)?.value
            if (!list.isNullOrEmpty()) IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        }
        LoadView(tracks, onRetry = { vm.invalidate("tracks:${item.id}"); tracks = Load.Loading; attempt++ }) { list ->
            LazyColumn(contentPadding = padding) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val thumb = item.thumbnail.ifEmpty { list.firstOrNull()?.thumbnail.orEmpty() }
                        Artwork(thumb, 200.dp, icon = item.kind.icon)
                        Spacer(Modifier.height(12.dp))
                        Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = item.subtitle.ifEmpty { item.kind.label }
                        Text("$sub • ${list.size} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(shape = RectangleShape, onClick = { vm.playList(list) }, enabled = list.isNotEmpty()) {
                                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                            }
                            FilledTonalButton(shape = RectangleShape, onClick = { vm.playShuffled(list) }, enabled = list.isNotEmpty()) {
                                Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                            }
                            FilledTonalButton(shape = RectangleShape, onClick = { vm.enqueue(list) }, enabled = list.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue all")
                            }
                        }
                    }
                }
                if (item.kind == Item.Kind.ARTIST && list.isNotEmpty()) item { SectionHeader("Songs") }
                itemsIndexed(list, key = { i, t -> "$i/${t.videoId}" }) { i, t ->
                    TrackRow(
                        vm, t, onClick = { vm.playList(list, i) },
                        showArt = item.kind != Item.Kind.ALBUM,
                        leading = if (item.kind == Item.Kind.ALBUM) {
                            { Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Text("${i + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        } else null,
                    )
                }
                items(sections, key = { "s" + it.title }) { Shelf(vm, it) }
                item { Spacer(Modifier.height(16.dp)) }
            }
            if (menu) TrackMenu(vm, list, item.title) { menu = false }
        }
    }
}
