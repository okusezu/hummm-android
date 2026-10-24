package com.okusezu.hummm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.okusezu.hummm.data.Item
import com.okusezu.hummm.data.Track

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun Artwork(url: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = RectangleShape, icon: ImageVector = Icons.Default.MusicNote) {
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotEmpty()) {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        } else Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    vm: AppViewModel,
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    showArt: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    val playing = vm.current?.videoId == track.videoId
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = leading ?: if (showArt) { { Artwork(track.thumbnail, 48.dp) } } else null,
        headlineContent = {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.SemiBold else null,
            )
        },
        supportingContent = {
            val bits = listOfNotNull(track.artist, track.duration.takeIf { it > 0 }?.let { fmtTime(it * 1000L) })
            Text(bits.joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
        },
    )
    if (menu) TrackMenu(vm, listOf(track), track.title, onDismiss = { menu = false })
}

@Composable
fun ItemRow(item: Item, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickableCompat(onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            Artwork(item.thumbnail, 48.dp, icon = item.kind.icon)
        },
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.subtitle.ifEmpty { item.kind.label }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

val Item.Kind.icon: ImageVector
    get() = when (this) {
        Item.Kind.ALBUM -> Icons.Default.Album
        Item.Kind.ARTIST -> Icons.Default.Person
        Item.Kind.PLAYLIST -> Icons.Default.PlaylistPlay
    }

val Item.Kind.label: String
    get() = when (this) {
        Item.Kind.ALBUM -> "Album"
        Item.Kind.ARTIST -> "Artist"
        Item.Kind.PLAYLIST -> "Playlist"
    }

/** Card for horizontal shelves (home feed, artist pages). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(title: String, subtitle: String, thumb: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier.width(148.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        Artwork(thumb, 136.dp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Actions for one or more tracks: the phone version of Tab / Shift+Tab / ctrl+p. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(vm: AppViewModel, tracks: List<Track>, title: String, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val single = tracks.singleOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (single != null) { Artwork(single.thumbnail, 48.dp); Spacer(Modifier.width(16.dp)) }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(single?.artist ?: "${tracks.size} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            fun act(block: () -> Unit) = { block(); onDismiss() }
            MenuRow(Icons.Default.PlaylistPlay, "Play next", act { vm.enqueueNext(tracks) })
            MenuRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", act { vm.enqueue(tracks) })
            if (single != null) {
                MenuRow(Icons.Default.Radio, "Start radio", act { vm.radio(single) })
                val liked = single.videoId in vm.likedIds
                MenuRow(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (liked) "Remove like" else "Like", act { vm.toggleLike(single) })
            }
            MenuRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") {
                if (!vm.music.authed) { vm.say("Adding to playlists needs sign-in. Open Library → Settings"); onDismiss() }
                else picking = true
            }
            if (single != null && single.albumId.isNotEmpty()) MenuRow(Icons.Default.Album, "Go to album", act { vm.goToAlbum(single) })
            if (single != null && single.artistIds.isNotEmpty()) MenuRow(Icons.Default.Person, "Go to artist", act { vm.goToArtist(single) })
        }
    }
    if (picking) PlaylistPicker(vm, tracks, onDismiss = { picking = false; onDismiss() })
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickableCompat(onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(label) },
    )
}

@Composable
fun PlaylistPicker(vm: AppViewModel, tracks: List<Track>, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Load<List<Item>>>(Load.Loading) }
    LaunchedEffect(Unit) { state = vm.load { vm.music.playlistsForAdd() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LoadView(state, Modifier.height(360.dp)) { lists ->
                if (lists.isEmpty()) Text("You don't have any playlists yet.")
                LazyColumn {
                    items(lists) { p -> ItemRow(p) { vm.addToPlaylist(p, tracks); onDismiss() } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SaveQueueDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true) },
        confirmButton = { TextButton(shape = RectangleShape, enabled = name.isNotBlank(), onClick = { onSave(name.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun <T> LoadView(
    state: Load<T>?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        null -> {}
        Load.Loading -> Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { Spinner(label = "loading") }
        is Load.Err -> Column(
            modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WaveLogo(live = false, color = MaterialTheme.colorScheme.outline)
            Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.needsSignIn && onSignIn != null) Button(shape = RectangleShape, onClick = onSignIn) { Text("Sign in") }
            else if (onRetry != null) TextButton(shape = RectangleShape, onClick = onRetry) { Text("Try again") }
        }
        is Load.Ok -> content(state.value)
    }
}

/** A section title followed by a rule, like the desktop app's panel borders. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** A tab label; the selected one is drawn in reverse video, like a terminal tab bar. */
@Composable
fun TermTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** On/off setting shown as a terminal checkbox: [x] or [ ]. */
@Composable
fun TermCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    Text(
        if (checked) "[x]" else "[ ]",
        Modifier.clickable { onChange(!checked) }.padding(8.dp),
        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
    )
}
