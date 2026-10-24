package com.okusezu.hummm.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.okusezu.hummm.BuildConfig
import com.okusezu.hummm.data.InnerTube
import com.okusezu.hummm.data.Item
import com.okusezu.hummm.data.METRICS
import com.okusezu.hummm.data.Streams
import com.okusezu.hummm.data.Track
import com.okusezu.hummm.playback.Discovery
import com.okusezu.hummm.playback.EqualizerInfo
import kotlinx.coroutines.launch

private val VIBE_IDEAS = listOf(
    "late night drive", "rainy day coding", "sunday morning coffee", "gym hype",
    "summer sunset", "deep focus", "friday party", "heartbreak", "jazzy dinner",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VibeScreen(vm: AppViewModel, padding: PaddingValues) {
    var text by rememberSaveable { mutableStateOf("") }
    val fm = LocalFocusManager.current
    val metric by vm.settings.metric.collectAsState()
    val auto by vm.settings.autoDiscover.collectAsState()
    val discovery by Discovery.status.collectAsState()
    Column(Modifier.fillMaxSize()) {
        HummmTopBar(vm, "Vibe")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Describe a vibe and hummm builds a queue for it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. late night drive") },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                singleLine = true, shape = RectangleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.startVibe(text); fm.clearFocus() }),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VIBE_IDEAS.forEach { idea ->
                    SuggestionChip(onClick = { text = idea; vm.startVibe(idea); fm.clearFocus() }, label = { Text(idea) })
                }
            }
            Button(shape = RectangleShape, onClick = { vm.startVibe(text); fm.clearFocus() }, enabled = text.isNotBlank() && !vm.vibeBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Play this vibe")
            }
            vm.vibeStatus?.let { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaveLogo(live = vm.vibeBusy, width = 40.dp, height = 16.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Discovery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("How to find new songs from what's playing:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                METRICS.forEach { m -> FilterChip(selected = m == metric, onClick = { vm.settings.setMetric(m) }, label = { Text(m) }) }
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Auto-discover") },
                supportingContent = { Text("Add songs automatically when the queue runs dry") },
                trailingContent = { TermCheck(auto) { vm.settings.setAutoDiscover(it) } },
                modifier = Modifier.padding(0.dp),
            )
            OutlinedButton(shape = RectangleShape, onClick = { vm.discoverNow(10) }, enabled = discovery !is Discovery.Status.Busy, modifier = Modifier.fillMaxWidth()) {
                Text("Discover 10 songs now")
            }
            val line = when (val d = discovery) {
                Discovery.Status.Idle -> null
                is Discovery.Status.Busy -> "Discovering (${d.metric})…"
                is Discovery.Status.Done -> "Discovered ${d.count} songs via ${d.metric}"
                is Discovery.Status.Failed -> d.message
            }
            line?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaveLogo(live = discovery is Discovery.Status.Busy, width = 40.dp, height = 16.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun LibraryScreen(vm: AppViewModel, padding: PaddingValues) {
    Column(Modifier.fillMaxSize()) {
        HummmTopBar(vm, "Library") {
            IconButton(onClick = { vm.push(Route.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        LazyColumn(contentPadding = padding) {
            if (vm.account == null) item {
                Box(Modifier.fillMaxWidth().padding(16.dp).border(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sign in to YouTube Music", style = MaterialTheme.typography.titleMedium)
                        Text("Your library, liked songs, likes and saving playlists need your account. Everything else works as a guest.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(shape = RectangleShape, onClick = { vm.push(Route.Login) }) { Text("Sign in") }
                    }
                }
            }
            val rows = listOf(
                Triple("liked", "Liked songs", Icons.Default.Favorite),
                Triple("songs", "Songs", Icons.Default.LibraryMusic),
                Triple("albums", "Albums", Icons.Default.Album),
                Triple("artists", "Artists", Icons.Default.Person),
                Triple("playlists", "Playlists", Icons.Default.PlaylistPlay),
            )
            items(rows) { (kind, label, icon) -> NavRow(icon, label) { vm.push(Route.LibraryList(kind)) } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { NavRow(Icons.AutoMirrored.Filled.QueueMusic, "Queue") { vm.playerOpen = true; vm.playerPage = PlayerPage.QUEUE } }
            item { NavRow(Icons.Default.Equalizer, "Equalizer") { vm.push(Route.Equalizer) } }
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickableRow(onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

@Composable
fun LibraryListScreen(vm: AppViewModel, kind: String, padding: PaddingValues) {
    val title = mapOf("liked" to "Liked songs", "songs" to "Songs", "albums" to "Albums", "artists" to "Artists", "playlists" to "Playlists")[kind] ?: kind
    var state by remember(kind) { mutableStateOf<Load<List<Any>>>(Load.Loading) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(kind, attempt) {
        state = vm.cached("lib:$kind") {
            when (kind) {
                "liked" -> vm.music.likedSongs()
                "songs" -> vm.music.librarySongs()
                "albums" -> vm.music.libraryAlbums()
                "artists" -> vm.music.libraryArtists()
                else -> vm.music.libraryPlaylists()
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title, onBack = { vm.back() })
        LoadView(state, onRetry = { vm.invalidate("lib:$kind"); state = Load.Loading; attempt++ }, onSignIn = { vm.push(Route.Login) }) { list ->
            val tracks = list.filterIsInstance<Track>()
            if (list.isEmpty()) Text("Nothing here yet.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = padding) {
                if (tracks.isNotEmpty()) item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(shape = RectangleShape, onClick = { vm.playList(tracks) }) { Text("Play all") }
                        OutlinedButton(shape = RectangleShape, onClick = { vm.playShuffled(tracks) }) { Text("Shuffle") }
                    }
                }
                itemsIndexed(list) { i, e ->
                    when (e) {
                        is Track -> TrackRow(vm, e, onClick = { vm.playList(tracks, i) })
                        is Item -> ItemRow(e) { vm.open(e) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, padding: PaddingValues) {
    val quality by vm.settings.quality.collectAsState()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Settings", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(padding)) {
            SectionHeader("Account")
            if (vm.account != null) {
                NavRow(Icons.Default.Logout, "Sign out", "Signed in as ${vm.account}") { vm.signOut() }
            } else {
                NavRow(Icons.Default.Login, "Sign in to YouTube Music", "Unlocks your library, likes and playlists") { vm.push(Route.Login) }
            }
            SectionHeader("Audio quality")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Streams.QUALITIES.forEach { q -> FilterChip(selected = q == quality, onClick = { vm.settings.setQuality(q) }, label = { Text(q) }) }
            }
            Text("Applies from the next song. Low saves mobile data.",
                Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("Sound")
            NavRow(Icons.Default.Equalizer, "Equalizer") { vm.push(Route.Equalizer) }
            SectionHeader("About")
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WaveLogo(live = vm.isPlaying)
                    Spacer(Modifier.width(12.dp))
                    Text("hummm ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                }
                Text("A vibe-driven YouTube Music player. The Android sibling of the hummm terminal app, which is modelled on vibez.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Streams are resolved with NewPipeExtractor; catalogue, lyrics and library come from YouTube Music.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Sign-in: the user logs in to Google inside a WebView and we keep the
 * youtube.com cookies, just as the desktop app reads them from a browser.
 * Google's redirect after login can end on any youtube.com page (often the
 * plain YouTube home), so every youtube.com page is checked for the login
 * cookie, and app links are kept from opening the YouTube app.
 * A paste box covers phones where Google refuses the embedded login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(vm: AppViewModel) {
    var verifying by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val tried = remember { mutableSetOf<String>() }
    fun tryCookie(cookie: String) {
        if (verifying) return
        verifying = true
        tried += cookie
        vm.signIn(cookie) { ok -> verifying = false; if (ok) vm.back() }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Sign in", onBack = { vm.back() }) {
            TextButton(shape = RectangleShape, onClick = { paste = !paste }) { Text(if (paste) "Use browser" else "Paste cookie") }
        }
        if (verifying) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Checking your login with YouTube Music…")
                }
            }
        } else if (paste) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("On a computer, open music.youtube.com signed in, press F12 → Network, click any “browse” request and copy the value of its Cookie request header.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth().height(200.dp), label = { Text("Cookie header") })
                Button(shape = RectangleShape, onClick = { tryCookie(pasted.trim().removePrefix("cookie:").removePrefix("Cookie:").trim()) }, enabled = pasted.isNotBlank()) { Text("Sign in") }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Without the WebView marker Google treats this as an ordinary Chrome tab.
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                // intent:// and vnd.youtube: links would hand off to the YouTube app.
                                request.url.scheme != "http" && request.url.scheme != "https"

                            override fun onPageFinished(view: WebView, url: String) = check(url)

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = check(url)

                            private fun check(url: String) {
                                val host = Uri.parse(url).host.orEmpty()
                                if (host != "youtube.com" && !host.endsWith(".youtube.com")) return
                                CookieManager.getInstance().flush()
                                val cookie = CookieManager.getInstance().getCookie(InnerTube.ORIGIN) ?: return
                                if (InnerTube.sapisid(cookie) != null && cookie !in tried) tryCookie(cookie)
                            }
                        }
                        loadUrl(LOGIN_URL)
                    }
                },
            )
        }
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(vm: AppViewModel, padding: PaddingValues) {
    val bands by EqualizerInfo.bands.collectAsState()
    val levels by vm.settings.eq.collectAsState()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Equalizer", onBack = { vm.back() }) {
            TextButton(shape = RectangleShape, onClick = { vm.settings.setEq(emptyList()) }) { Text("Reset") }
        }
        val b = bands
        if (b == null) {
            Text("The equalizer becomes available once something has played.",
                Modifier.padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val current = if (levels.size == b.centersHz.size) levels else List(b.centersHz.size) { 0 }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(padding).padding(16.dp)) {
            b.centersHz.forEachIndexed { i, hz ->
                val label = if (hz >= 1000) "${hz / 1000}${if (hz % 1000 >= 100) ".${hz % 1000 / 100}" else ""} kHz" else "$hz Hz"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = current[i].toFloat(),
                        onValueChange = { v -> vm.settings.setEq(current.toMutableList().also { it[i] = (v / 100).toInt() * 100 }) },
                        valueRange = b.minLevel.toFloat()..b.maxLevel.toFloat(),
                        modifier = Modifier.weight(1f),
                        thumb = { Box(Modifier.size(width = 8.dp, height = 22.dp).background(MaterialTheme.colorScheme.primary)) },
                    )
                    Text("%+.0f dB".format(current[i] / 100f), Modifier.width(60.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Uses your phone's built-in equalizer (${b.centersHz.size} bands). Saved between sessions.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
